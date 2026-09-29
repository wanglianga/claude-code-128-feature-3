package com.dentallab.service;

import com.dentallab.config.BizException;
import com.dentallab.model.*;
import com.dentallab.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 色号争议处理：患者认为义齿颜色与邻牙不一致时，
 * 服务调取取色照片、医生确认记录、材料批次与自然光复拍形成证据链；
 * 判定重新制作的，原件状态与费用承担单独记录，返工单同步质检并保留原义齿去向、新材料批次；
 * 患者试戴反馈回到同一牙位档案，医生复核关闭、质检主管复核、质检结论归档。
 */
@Service
public class ShadeDisputeService {

    private static final List<ShadeDisputeStatus> ACTIVE_STATUSES = List.of(
            ShadeDisputeStatus.OPEN, ShadeDisputeStatus.REMAKE_IN_PROGRESS,
            ShadeDisputeStatus.CLOSED, ShadeDisputeStatus.QC_REVIEWED);

    private final ShadeDisputeRepo disputeRepo;
    private final DentureOrderRepo orderRepo;
    private final ToothItemRepo itemRepo;
    private final StageRecordRepo stageRepo;
    private final MaterialBatchRepo batchRepo;
    private final ReworkRecordRepo reworkRepo;
    private final OrderExceptionRepo exceptionRepo;
    private final TryInRecordRepo tryInRepo;
    private final AuthService authService;

    public ShadeDisputeService(ShadeDisputeRepo disputeRepo, DentureOrderRepo orderRepo,
                               ToothItemRepo itemRepo, StageRecordRepo stageRepo,
                               MaterialBatchRepo batchRepo, ReworkRecordRepo reworkRepo,
                               OrderExceptionRepo exceptionRepo, TryInRecordRepo tryInRepo,
                               AuthService authService) {
        this.disputeRepo = disputeRepo;
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.stageRepo = stageRepo;
        this.batchRepo = batchRepo;
        this.reworkRepo = reworkRepo;
        this.exceptionRepo = exceptionRepo;
        this.tryInRepo = tryInRepo;
        this.authService = authService;
    }

    /** 发起色号争议：服务自动调取取色照片、医生确认记录与材料批次。 */
    @Transactional
    public Map<String, Object> open(Account user, Long orderId, Long toothItemId,
                                    String patientClaim, String observedShade, List<String> shadeTakingPhotos) {
        authService.requireRole(user, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN, Role.CUSTOMER_SERVICE);
        DentureOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> BizException.notFound("订单#" + orderId));
        if (user.getClinic() != null && !user.getClinic().getId().equals(order.getClinic().getId())
                && user.getRole() != Role.CUSTOMER_SERVICE) {
            throw BizException.forbidden("只能对本诊所订单发起色号争议");
        }
        ToothItem item = itemRepo.findById(toothItemId)
                .orElseThrow(() -> BizException.notFound("牙位明细#" + toothItemId));
        if (!item.getOrder().getId().equals(orderId)) {
            throw new BizException("牙位明细#" + toothItemId + "不属于订单#" + orderId);
        }
        if (disputeRepo.existsByToothItemIdAndStatusIn(toothItemId, ACTIVE_STATUSES)) {
            throw new BizException("牙位 " + item.getToothPosition() + " 已存在未归档的色号争议，不能重复发起");
        }

        ShadeDispute dispute = new ShadeDispute();
        dispute.setOrder(order);
        dispute.setToothItem(item);
        dispute.setPatientClaim(patientClaim);
        dispute.setOrderedShade(order.getShade());
        dispute.setObservedShade(observedShade);
        dispute.setReportedBy(user);
        dispute.setReportedAt(LocalDateTime.now());

        // 服务调取证据链：取色照片、医生确认记录、材料批次
        List<StageRecord> stages = stageRepo.findByToothItemIdOrderByIdAsc(toothItemId);
        dispute.setShadeTakingPhotos(collectShadeTakingPhotos(stages, shadeTakingPhotos));
        dispute.setDoctorConfirmRecord(collectDoctorConfirm(stages));
        MaterialBatch originalBatch = latestBatch(stages);
        if (originalBatch != null) {
            dispute.setMaterialBatch(originalBatch);
            dispute.setMaterialBatchSummary(originalBatch.getMaterial().getName()
                    + " 批次[" + originalBatch.getBatchNo() + "]");
        } else if (!stages.isEmpty()) {
            dispute.setMaterialBatchSummary("工序中未登记材料批次");
        } else {
            dispute.setMaterialBatchSummary("该牙位尚无工序与材料记录");
        }
        disputeRepo.save(dispute);

        // 同步登记色号争议异常事件，串联各方
        OrderException event = new OrderException();
        event.setOrder(order);
        event.setToothItem(item);
        event.setType(ExceptionType.SHADE_DISPUTE);
        event.setDescription("患者认为义齿颜色与邻牙不一致（牙位 " + item.getToothPosition()
                + "，下单色号 " + order.getShade()
                + (observedShade == null ? "" : "，观察色号 " + observedShade) + "）: " + patientClaim);
        event.setReportedBy(user);
        exceptionRepo.save(event);
        return Views.shadeDispute(dispute);
    }

    /** 自然光复拍：补充自然光下照片与说明。 */
    @Transactional
    public Map<String, Object> naturalLightRetake(Account user, Long disputeId,
                                                  List<String> photos, String note) {
        authService.requireRole(user, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN, Role.CUSTOMER_SERVICE,
                Role.TECHNICIAN, Role.LAB_ADMIN, Role.LAB_RECEIVER);
        ShadeDispute dispute = getDispute(disputeId);
        if (user.getClinic() != null && !user.getClinic().getId().equals(dispute.getOrder().getClinic().getId())
                && user.getRole() == Role.CLINIC_DOCTOR) {
            throw BizException.forbidden("只能补充本诊所订单的自然光复拍");
        }
        if (photos == null || photos.isEmpty()) {
            throw new BizException("请至少上传一张自然光下复拍照片 naturalLightPhotos");
        }
        if (dispute.getStatus() != ShadeDisputeStatus.OPEN) {
            throw new BizException("争议已判定，不能再补充自然光复拍");
        }
        dispute.setNaturalLightPhotos(String.join(",", photos));
        dispute.setNaturalLightNote(note);
        dispute.setNaturalLightTakenBy(user);
        dispute.setNaturalLightAt(LocalDateTime.now());
        touch(dispute);
        disputeRepo.save(dispute);
        return Views.shadeDispute(dispute);
    }

    /**
     * 客服/技工所管理员判定争议。
     * REMAKE：登记原件状态、原义齿去向、费用承担与新材料批次，创建返工单并同步质检，牙位打回重做。
     * NO_REMAKE：维持原义齿，等待医生复核关闭。
     */
    @Transactional
    public Map<String, Object> decide(Account user, Long disputeId, ShadeDisputeDecision decision,
                                      String remakeShade, String decisionNote, String originalDentureStatus,
                                      OriginalDentureDisposition disposition, Responsibility costResponsibility,
                                      BigDecimal remakeCost, Long newMaterialBatchId) {
        authService.requireRole(user, Role.CUSTOMER_SERVICE, Role.LAB_ADMIN);
        ShadeDispute dispute = getDispute(disputeId);
        if (dispute.getStatus() != ShadeDisputeStatus.OPEN) {
            throw new BizException("争议已判定，不能重复判定");
        }
        dispute.setDecision(decision);
        dispute.setRemakeShade(remakeShade);
        dispute.setDecisionNote(decisionNote);
        dispute.setDecidedBy(user);
        dispute.setDecidedAt(LocalDateTime.now());

        if (decision == ShadeDisputeDecision.REMAKE) {
            if (originalDentureStatus == null || originalDentureStatus.isBlank()) {
                throw new BizException("重新制作必须单独记录原件状态 originalDentureStatus");
            }
            if (disposition == null) {
                throw new BizException("重新制作必须登记原义齿去向 originalDentureDisposition");
            }
            if (costResponsibility == null || costResponsibility == Responsibility.PENDING) {
                throw new BizException("重新制作必须明确费用承担方 costResponsibility（诊所/技工所/双方共担）");
            }
            if (remakeCost == null || remakeCost.signum() < 0) {
                throw new BizException("重做费用 remakeCost 必须为非负数");
            }
            remakeCost = remakeCost.setScale(2, RoundingMode.HALF_UP);
            MaterialBatch newBatch = null;
            if (newMaterialBatchId != null) {
                newBatch = batchRepo.findById(newMaterialBatchId)
                        .orElseThrow(() -> BizException.notFound("新材料批次#" + newMaterialBatchId));
            }

            // 费用承担单独记录到争议单
            dispute.setOriginalDentureStatus(originalDentureStatus);
            dispute.setOriginalDentureDisposition(disposition);
            dispute.setCostResponsibility(costResponsibility);
            dispute.setRemakeCost(remakeCost);
            applyShares(dispute, costResponsibility, remakeCost);
            dispute.setNewMaterialBatch(newBatch);

            // 创建返工单（牙位打回），返工记录同步质检
            ToothItem item = dispute.getToothItem();
            List<ReworkRecord> openReworks =
                    reworkRepo.findByToothItemIdAndStatus(item.getId(), ReworkStatus.OPEN);
            if (!openReworks.isEmpty()) {
                throw new BizException("牙位 " + item.getToothPosition() + " 已有进行中的返工单#"
                        + openReworks.get(0).getId());
            }
            ReworkRecord rework = new ReworkRecord();
            rework.setOrder(dispute.getOrder());
            rework.setToothItem(item);
            rework.setReasonType(ReworkReasonType.SHADE_DISPUTE);
            rework.setReason("色号争议重新制作（牙位 " + item.getToothPosition()
                    + "，下单色号 " + dispute.getOrderedShade()
                    + (remakeShade == null ? "" : "→重做色号 " + remakeShade) + "）: " + patientClaimBrief(dispute));
            rework.setOriginalDentureStatus(originalDentureStatus);
            rework.setOriginalDentureDisposition(disposition);
            rework.setNewMaterialBatch(newBatch);
            // 费用承担同步返工单（进入结算），返工记录同步质检
            rework.setResponsibility(costResponsibility);
            rework.setReworkCost(remakeCost);
            rework.setClinicShare(dispute.getClinicShare());
            rework.setLabShare(dispute.getLabShare());
            rework.setQcSynced(true);
            rework.setQcSyncedAt(LocalDateTime.now());
            reworkRepo.save(rework);
            dispute.setRework(rework);

            item.setStatus(ToothItemStatus.REWORKING);
            item.setCurrentStage(null);
            item.setUpdatedAt(LocalDateTime.now());
            itemRepo.save(item);
            DentureOrder order = dispute.getOrder();
            if (order.getStatus() != OrderStatus.IN_PRODUCTION) {
                order.setStatus(OrderStatus.IN_PRODUCTION);
                order.setUpdatedAt(LocalDateTime.now());
                orderRepo.save(order);
            }
            dispute.setStatus(ShadeDisputeStatus.REMAKE_IN_PROGRESS);
        } else {
            // 维持原义齿：不创建返工，等待医生复核关闭
            dispute.setStatus(ShadeDisputeStatus.OPEN);
        }
        touch(dispute);
        disputeRepo.save(dispute);
        return Views.shadeDispute(dispute);
    }

    /** 患者试戴反馈回到同一牙位档案（也可由试戴登记自动回写）。 */
    @Transactional
    public Map<String, Object> recordPatientTryIn(Account doctor, Long disputeId, String feedback,
                                                  Integer score, Long tryInRecordId) {
        authService.requireRole(doctor, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN);
        ShadeDispute dispute = getDispute(disputeId);
        if (doctor.getClinic() == null
                || !doctor.getClinic().getId().equals(dispute.getOrder().getClinic().getId())) {
            throw BizException.forbidden("只能记录本诊所订单的患者试戴反馈");
        }
        if (score != null && (score < 1 || score > 5)) {
            throw new BizException("患者体验评分 patientTryInScore 必须在 1-5 之间");
        }
        dispute.setPatientTryInFeedback(feedback);
        dispute.setPatientTryInScore(score);
        dispute.setPatientTryInAt(LocalDateTime.now());
        if (tryInRecordId != null) {
            TryInRecord record = tryInRepo.findById(tryInRecordId)
                    .orElseThrow(() -> BizException.notFound("试戴记录#" + tryInRecordId));
            if (record.getToothItem() == null
                    || !record.getToothItem().getId().equals(dispute.getToothItem().getId())) {
                throw new BizException("试戴记录#" + tryInRecordId + "不属于争议牙位 "
                        + dispute.getToothItem().getToothPosition());
            }
            dispute.setTryInRecord(record);
        }
        touch(dispute);
        disputeRepo.save(dispute);
        return Views.shadeDispute(dispute);
    }

    /** 医生复核后关闭。重新制作的牙位须已重做完成（返工单关闭）。 */
    @Transactional
    public Map<String, Object> doctorClose(Account doctor, Long disputeId, String note) {
        authService.requireRole(doctor, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN);
        ShadeDispute dispute = getDispute(disputeId);
        if (doctor.getClinic() == null
                || !doctor.getClinic().getId().equals(dispute.getOrder().getClinic().getId())) {
            throw BizException.forbidden("只能复核关闭本诊所订单的色号争议");
        }
        if (dispute.getDecision() == null) {
            throw new BizException("争议尚未判定，医生不能复核关闭");
        }
        if (dispute.getDecision() == ShadeDisputeDecision.REMAKE) {
            if (dispute.getRework() == null || dispute.getRework().getStatus() != ReworkStatus.COMPLETED) {
                throw new BizException("重新制作尚未完成（返工单未关闭），医生不能复核关闭");
            }
        }
        dispute.setDoctorClosed(true);
        dispute.setDoctorClosedBy(doctor);
        dispute.setDoctorClosedAt(LocalDateTime.now());
        dispute.setDoctorCloseNote(note);
        dispute.setStatus(ShadeDisputeStatus.CLOSED);
        touch(dispute);
        disputeRepo.save(dispute);
        return Views.shadeDispute(dispute);
    }

    /** 质检主管复核（医生关闭后）。 */
    @Transactional
    public Map<String, Object> qcReview(Account qc, Long disputeId, String note) {
        authService.requireRole(qc, Role.QC_INSPECTOR, Role.LAB_ADMIN);
        ShadeDispute dispute = getDispute(disputeId);
        if (!dispute.isDoctorClosed()) {
            throw new BizException("医生尚未复核关闭，质检主管不能复核");
        }
        if (dispute.isQcReviewed()) {
            throw new BizException("质检主管已复核，不能重复复核");
        }
        dispute.setQcReviewed(true);
        dispute.setQcReviewedBy(qc);
        dispute.setQcReviewedAt(LocalDateTime.now());
        dispute.setQcReviewNote(note);
        dispute.setStatus(ShadeDisputeStatus.QC_REVIEWED);
        touch(dispute);
        disputeRepo.save(dispute);
        return Views.shadeDispute(dispute);
    }

    /** 质检结论归档（质检主管复核通过后），并把关联色号争议异常事件标记已解决。 */
    @Transactional
    public Map<String, Object> archive(Account qc, Long disputeId, String conclusion) {
        authService.requireRole(qc, Role.QC_INSPECTOR, Role.LAB_ADMIN);
        ShadeDispute dispute = getDispute(disputeId);
        if (!dispute.isQcReviewed()) {
            throw new BizException("质检主管尚未复核，不能归档");
        }
        if (conclusion == null || conclusion.isBlank()) {
            throw new BizException("归档必须填写质检结论 archiveConclusion");
        }
        dispute.setArchived(true);
        dispute.setArchivedBy(qc);
        dispute.setArchivedAt(LocalDateTime.now());
        dispute.setArchiveConclusion(conclusion);
        dispute.setStatus(ShadeDisputeStatus.ARCHIVED);
        touch(dispute);
        disputeRepo.save(dispute);

        for (OrderException e : exceptionRepo.findByOrderIdOrderByIdAsc(dispute.getOrder().getId())) {
            if (e.getType() == ExceptionType.SHADE_DISPUTE
                    && e.getToothItem() != null
                    && e.getToothItem().getId().equals(dispute.getToothItem().getId())
                    && e.getStatus() != ExceptionStatus.RESOLVED) {
                e.setStatus(ExceptionStatus.RESOLVED);
                e.setHandledBy(qc);
                e.setResolution("色号争议#" + dispute.getId() + " 已质检结论归档: " + conclusion);
                e.setHandledAt(LocalDateTime.now());
                exceptionRepo.save(e);
            }
        }
        return Views.shadeDispute(dispute);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(Long disputeId) {
        return Views.shadeDispute(getDispute(disputeId));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listByOrder(Long orderId) {
        return disputeRepo.findByOrderIdOrderByIdAsc(orderId).stream()
                .map(Views::shadeDispute).collect(Collectors.toList());
    }

    /** 质检工作台：返工记录同步质检——进行中的色号争议返工。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> qcWorkbench(Account user) {
        authService.requireRole(user, Role.QC_INSPECTOR, Role.LAB_ADMIN);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ShadeDisputeStatus st : ACTIVE_STATUSES) {
            disputeRepo.findByStatusOrderByIdDesc(st).stream()
                    .filter(d -> d.getRework() != null && d.getRework().isQcSynced())
                    .map(Views::shadeDispute).forEach(result::add);
        }
        return result;
    }

    // ---- 内部逻辑 ----

    private ShadeDispute getDispute(Long id) {
        return disputeRepo.findById(id).orElseThrow(() -> BizException.notFound("色号争议#" + id));
    }

    private void touch(ShadeDispute dispute) {
        dispute.setUpdatedAt(LocalDateTime.now());
    }

    private void applyShares(ShadeDispute dispute, Responsibility responsibility, BigDecimal cost) {
        switch (responsibility) {
            case CLINIC -> {
                dispute.setClinicShare(cost);
                dispute.setLabShare(BigDecimal.ZERO);
            }
            case LAB -> {
                dispute.setClinicShare(BigDecimal.ZERO);
                dispute.setLabShare(cost);
            }
            case SHARED -> {
                BigDecimal half = cost.divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
                dispute.setClinicShare(half);
                dispute.setLabShare(cost.subtract(half));
            }
            default -> throw new BizException("非法费用承担方");
        }
    }

    private String patientClaimBrief(ShadeDispute d) {
        String c = d.getPatientClaim();
        return c == null ? "" : (c.length() > 120 ? c.substring(0, 120) : c);
    }

    /** 取色照片：优先用显式上传，否则从工序照片中调取。 */
    private String collectShadeTakingPhotos(List<StageRecord> stages, List<String> explicit) {
        if (explicit != null && !explicit.isEmpty()) {
            return String.join(",", explicit);
        }
        List<String> photos = stages.stream()
                .filter(s -> s.getPhotos() != null && !s.getPhotos().isBlank())
                .flatMap(s -> Arrays.stream(s.getPhotos().split(",")))
                .map(String::trim).filter(s -> !s.isEmpty())
                .distinct().collect(Collectors.toList());
        return photos.isEmpty() ? null : String.join(",", photos);
    }

    /** 医生确认记录：DOCTOR_CONFIRM 工序的确认人、意见与时间。 */
    private String collectDoctorConfirm(List<StageRecord> stages) {
        return stages.stream().filter(s -> s.getStage() == StageType.DOCTOR_CONFIRM).findFirst()
                .map(s -> "确认人=" + s.getOperator().getDisplayName()
                        + "；确认意见=" + (s.getNote() == null ? "" : s.getNote())
                        + "；确认时间=" + (s.getFinishedAt() == null ? s.getStartedAt() : s.getFinishedAt()))
                .orElse("该牙位暂无医生确认记录");
    }

    private MaterialBatch latestBatch(List<StageRecord> stages) {
        MaterialBatch batch = null;
        for (StageRecord s : stages) {
            if (s.getMaterialBatch() != null) {
                batch = s.getMaterialBatch();
            }
        }
        return batch;
    }
}
