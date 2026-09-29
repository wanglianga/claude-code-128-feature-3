package com.dentallab.service;

import com.dentallab.config.BizException;
import com.dentallab.model.*;
import com.dentallab.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 试戴：诊所回填试戴结果与患者体验；不密合自动触发异常与返工。 */
@Service
public class TryInService {

    private final TryInRecordRepo tryInRepo;
    private final DentureOrderRepo orderRepo;
    private final ToothItemRepo itemRepo;
    private final OrderExceptionRepo exceptionRepo;
    private final ReworkRecordRepo reworkRepo;
    private final ShadeDisputeRepo shadeDisputeRepo;
    private final AuthService authService;

    public TryInService(TryInRecordRepo tryInRepo, DentureOrderRepo orderRepo, ToothItemRepo itemRepo,
                        OrderExceptionRepo exceptionRepo, ReworkRecordRepo reworkRepo,
                        ShadeDisputeRepo shadeDisputeRepo, AuthService authService) {
        this.tryInRepo = tryInRepo;
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.exceptionRepo = exceptionRepo;
        this.reworkRepo = reworkRepo;
        this.shadeDisputeRepo = shadeDisputeRepo;
        this.authService = authService;
    }

    /**
     * 记录试戴结果。toothItemId 为空表示整单试戴。
     * 不密合：牙位打回返工（责任待判定），并自动登记试戴不密合异常。
     */
    @Transactional
    public Map<String, Object> recordResult(Account doctor, Long orderId, Long toothItemId,
                                            TryInResult result, String feedback, Integer experienceScore) {
        authService.requireRole(doctor, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN);
        DentureOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> BizException.notFound("订单#" + orderId));
        if (doctor.getClinic() == null || !doctor.getClinic().getId().equals(order.getClinic().getId())) {
            throw BizException.forbidden("只能记录本诊所订单的试戴结果");
        }
        if (experienceScore != null && (experienceScore < 1 || experienceScore > 5)) {
            throw new BizException("患者体验评分 experienceScore 必须在 1-5 之间");
        }
        ToothItem item = null;
        if (toothItemId != null) {
            item = itemRepo.findById(toothItemId)
                    .orElseThrow(() -> BizException.notFound("牙位明细#" + toothItemId));
            if (!item.getOrder().getId().equals(orderId)) {
                throw new BizException("牙位明细#" + toothItemId + "不属于订单#" + orderId);
            }
        }

        TryInRecord record = new TryInRecord();
        record.setOrder(order);
        record.setToothItem(item);
        record.setScheduledAt(LocalDateTime.now());
        record.setActualAt(LocalDateTime.now());
        record.setResult(result);
        record.setFeedback(feedback);
        record.setExperienceScore(experienceScore);
        tryInRepo.save(record);

        // 患者试戴反馈回到同一牙位档案：若该牙位存在色号争议，回写到争议单
        if (item != null) {
            shadeDisputeRepo.findByToothItemIdOrderByIdAsc(item.getId()).stream()
                    .filter(d -> !d.isArchived())
                    .reduce((first, second) -> second)
                    .ifPresent(dispute -> {
                        dispute.setTryInRecord(record);
                        dispute.setPatientTryInFeedback(feedback);
                        dispute.setPatientTryInScore(experienceScore);
                        dispute.setPatientTryInAt(record.getActualAt());
                        shadeDisputeRepo.save(dispute);
                    });
        }

        List<ToothItem> targets = item != null ? List.of(item) : itemRepo.findByOrderIdOrderByIdAsc(orderId);
        if (result == TryInResult.FIT) {
            for (ToothItem t : targets) {
                t.setStatus(ToothItemStatus.TRY_IN_OK);
                t.setUpdatedAt(LocalDateTime.now());
                itemRepo.save(t);
            }
        } else {
            for (ToothItem t : targets) {
                t.setStatus(ToothItemStatus.REWORKING);
                t.setCurrentStage(null);
                t.setUpdatedAt(LocalDateTime.now());
                itemRepo.save(t);
                if (reworkRepo.findByToothItemIdAndStatus(t.getId(), ReworkStatus.OPEN).isEmpty()) {
                    ReworkRecord rework = new ReworkRecord();
                    rework.setOrder(order);
                    rework.setToothItem(t);
                    rework.setReasonType(ReworkReasonType.TRY_IN_MISFIT);
                    rework.setReason("试戴不密合: " + (feedback == null ? "患者试戴不密合" : feedback));
                    reworkRepo.save(rework);
                }
            }
            OrderException event = new OrderException();
            event.setOrder(order);
            event.setToothItem(item);
            event.setType(ExceptionType.TRY_IN_MISFIT);
            event.setDescription("试戴不密合" + (item != null ? "（牙位 " + item.getToothPosition() + "）" : "")
                    + ": " + (feedback == null ? "" : feedback));
            event.setReportedBy(doctor);
            exceptionRepo.save(event);
        }

        refreshOrderAfterTryIn(order);
        return Views.tryIn(record);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listByOrder(Long orderId) {
        return tryInRepo.findByOrderIdOrderByIdAsc(orderId).stream()
                .map(Views::tryIn).collect(Collectors.toList());
    }

    private void refreshOrderAfterTryIn(DentureOrder order) {
        List<ToothItem> items = itemRepo.findByOrderIdOrderByIdAsc(order.getId());
        boolean allOk = items.stream().allMatch(i -> i.getStatus() == ToothItemStatus.TRY_IN_OK);
        boolean anyReworking = items.stream().anyMatch(i -> i.getStatus() == ToothItemStatus.REWORKING);
        if (allOk && !items.isEmpty()) {
            order.setStatus(OrderStatus.COMPLETED);
            order.setCompletedAt(LocalDateTime.now());
        } else if (anyReworking) {
            order.setStatus(OrderStatus.IN_PRODUCTION);
        }
        order.setUpdatedAt(LocalDateTime.now());
        orderRepo.save(order);
    }
}
