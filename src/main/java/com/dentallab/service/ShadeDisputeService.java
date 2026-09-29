package com.dentallab.service;

import com.dentallab.config.BizException;
import com.dentallab.model.*;
import com.dentallab.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 色号争议处理：患者认为义齿颜色与邻牙不一致时，
 * 调取取色照片、医生确认记录、材料批次与自然光复拍；
 * 重新制作时单独记录原件状态、费用承担、原义齿去向与新材料批次；
 * 重做件患者试戴反馈回到同一牙位档案，医生复核关闭、质检主管复核归档。
 */
@Service
public class ShadeDisputeService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ShadeDisputeRepo disputeRepo;
    private final DentureOrderRepo orderRepo;
    private final ToothItemRepo itemRepo;
    private final OrderExceptionRepo exceptionRepo;
    private final StageRecordRepo stageRepo;
    private final ReworkRecordRepo reworkRepo;
    private final MaterialBatchRepo batchRepo;
    private final AuthService authService;

    public ShadeDisputeService(ShadeDisputeRepo disputeRepo, DentureOrderRepo orderRepo, ToothItemRepo itemRepo,
                               OrderExceptionRepo exceptionRepo, StageRecordRepo stageRepo,
                               ReworkRecordRepo reworkRepo, MaterialBatchRepo batchRepo,
                               AuthService authService) {
        this.disputeRepo = disputeRepo;
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.exceptionRepo = exceptionRepo;
        this.stageRepo = stageRepo;
        this.reworkRepo = reworkRepo;
        this.batchRepo = batchRepo;
        this.authService = authService;
    }

    /** 发起色号争议（自动登记 SHADE_DISPUTE 异常事件）。义齿成品已出（八道工序走完）后才可发起。 */
    @Transactional
    public Map<String, Object> open(Account user, Long orderId, Long toothItemId,
                                    String patientClaim, String claimedShade) {
        authService.requireRole(user, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN,
                Role.CUSTOMER_SERVICE, Role.LAB_ADMIN, Role.QC_INSPECTOR, Role.QC_MANAGER);
        DentureOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> BizException.notFound("订单#" + orderId));
        if (user.getClinic() != null && !user.getClinic().getId().equals(order.getClinic().getId())) {
            throw BizException.forbidden("只能对本诊所订单发起色号争议");
        }
        ToothItem item = itemRepo.findById(toothItemId)
                .orElseThrow(() -> BizException.notFound("牙位明细#" + toothItemId));
        if (!item.getOrder().getId().equals(orderId)) {
            throw new BizException("牙位明细#" + toothItemId + "不属于订单#" + orderId);
        }
        if (item.getCurrentStage() != StageType.DELIVERY) {
            throw new BizException("牙位 " + item.getToothPosition() + " 尚未完成制作（成品未出），"
                    + "当前工序: " + (item.getCurrentStage() == null ? "未开始" : item.getCurrentStage().getLabel()));
        }
        List<ShadeDispute> active = disputeRepo.findByToothItemIdAndStatusNot(toothItemId, ShadeDisputeStatus.ARCHIVED);
        if (!active.isEmpty()) {
            throw new BizException("牙位 " + item.getToothPosition() + " 已有进行中的色号争议#" + active.get(0).getId());
        }

        ShadeDispute dispute = new ShadeDispute();
        dispute.setOrder(order);
        dispute.setToothItem(item);
        dispute.setPatientClaim(patientClaim);
        dispute.setClaimedShade(claimedShade);
        dispute.setStatus(ShadeDisputeStatus.OPEN);
        disputeRepo.save(dispute);

        OrderException event = new OrderException();
        event.setOrder(order);
        event.setToothItem(item);
        event.setType(ExceptionType.SHADE_DISPUTE);
        event.setDescription("患者认为牙位 " + item.getToothPosition() + " 义齿颜色与邻牙不一致（原订单色号: "
                + (order.getShade() == null ? "未指定" : order.getShade())
                + (claimedShade == null ? "" : "；患者期望色号: " + claimedShade) + "）: " + patientClaim);
        event.setReportedBy(user);
        exceptionRepo.save(event);
        return Views.shadeDispute(dispute);
    }

    /**
     * 调取取证：服务自动归集该牙位档案中的取色照片（各工序照片）、
     * 医生确认记录（DOCTOR_CONFIRM 工序）与使用的材料批次，可补充提交取色照片。
     */
    @Transactional
    public Map<String, Object> collectEvidence(Account user, Long disputeId, List<String> extraShadePhotos) {
        authService.requireRole(user, Role.CUSTOMER_SERVICE, Role.LAB_ADMIN,
                Role.QC_INSPECTOR, Role.QC_MANAGER, Role.TECHNICIAN);
        ShadeDispute dispute = getDispute(disputeId);
        if (dispute.getStatus() != ShadeDisputeStatus.OPEN) {
            throw new BizException("争议单已完成取证，当前状态: " + dispute.getStatus().getLabel());
        }
        Long itemId = dispute.getToothItem().getId();
        List<StageRecord> stages = stageRepo.findByToothItemIdOrderByIdAsc(itemId);

        // 取色照片：归集牙位档案全部工序照片（比色/取色照片通常出现在扫描设计、医生确认环节）
        LinkedHashSet<String> photos = new LinkedHashSet<>();
        for (StageRecord s : stages) {
            if (s.getPhotos() != null && !s.getPhotos().isBlank()) {
                Arrays.stream(s.getPhotos().split(",")).map(String::trim).filter(p -> !p.isEmpty()).forEach(photos::add);
            }
        }
        if (extraShadePhotos != null) {
            photos.addAll(extraShadePhotos);
        }
        if (photos.isEmpty()) {
            throw new BizException("牙位档案中暂无取色照片，请在 shadePhotos 中补充提交");
        }
        dispute.setShadePhotos(String.join(",", photos));

        // 医生确认记录快照
        String confirmSnapshot = stages.stream()
                .filter(s -> s.getStage() == StageType.DOCTOR_CONFIRM)
                .map(s -> "工序记录#" + s.getId() + " 医生确认｜确认人:" + s.getOperator().getDisplayName()
                        + "｜时间:" + fmt(s.getFinishedAt()) + "｜意见:" + nullToDash(s.getNote()))
                .collect(Collectors.joining(" ;; "));
        if (confirmSnapshot.isBlank()) {
            throw new BizException("牙位档案缺少医生确认记录，无法完成取证");
        }
        dispute.setDoctorConfirmRecord(confirmSnapshot);

        // 材料批次快照
        String batchSnapshot = stages.stream()
                .filter(s -> s.getMaterialBatch() != null)
                .map(s -> "工序记录#" + s.getId() + " " + s.getStage().getLabel()
                        + "｜材料:" + s.getMaterialBatch().getMaterial().getName()
                        + "｜批次:" + s.getMaterialBatch().getBatchNo()
                        + "｜用量:" + s.getMaterialQty()
                        + "｜操作人:" + s.getOperator().getDisplayName()
                        + "｜时间:" + fmt(s.getFinishedAt() == null ? s.getStartedAt() : s.getFinishedAt()))
                .collect(Collectors.joining(" ;; "));
        dispute.setMaterialBatchRecord(batchSnapshot.isBlank() ? "档案中暂无材料批次使用记录" : batchSnapshot);

        dispute.setCollectedBy(user);
        dispute.setCollectedAt(LocalDateTime.now());
        dispute.setStatus(ShadeDisputeStatus.EVIDENCE_COLLECTED);
        disputeRepo.save(dispute);
        return Views.shadeDispute(dispute);
    }

    /** 自然光复拍：在自然光环境下重新拍摄义齿与邻牙对比照片。 */
    @Transactional
    public Map<String, Object> reshoot(Account user, Long disputeId, List<String> photos, String note) {
        authService.requireRole(user, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN,
                Role.CUSTOMER_SERVICE, Role.LAB_ADMIN);
        ShadeDispute dispute = getDispute(disputeId);
        if (user.getClinic() != null && !user.getClinic().getId().equals(dispute.getOrder().getClinic().getId())) {
            throw BizException.forbidden("只能对本诊所订单的色号争议进行复拍");
        }
        if (dispute.getStatus() != ShadeDisputeStatus.EVIDENCE_COLLECTED) {
            throw new BizException("请先完成调取取证，当前状态: " + dispute.getStatus().getLabel());
        }
        if (photos == null || photos.isEmpty()) {
            throw new BizException("请至少上传一张自然光复拍照片 photos");
        }
        dispute.setNaturalLightPhotos(String.join(",", photos));
        dispute.setReshootNote(note);
        dispute.setReshotBy(user);
        dispute.setReshotAt(LocalDateTime.now());
        dispute.setStatus(ShadeDisputeStatus.RESHOT);
        disputeRepo.save(dispute);
        return Views.shadeDispute(dispute);
    }

    /**
     * 重新制作判定。
     * 重做：记录新色号、原义齿状态与去向、费用承担（单独记录并同步返工单用于结算）、新材料批次，
     *       同时创建返工单并把牙位打回生产线；不重做：记录沟通结论，转医生复核关闭。
     */
    @Transactional
    public Map<String, Object> decide(Account user, Long disputeId, boolean remake, String targetShade,
                                      String originalCondition, OriginalDentureDisposition disposition,
                                      Responsibility responsibility, BigDecimal remakeCost,
                                      Long newMaterialBatchId, String decisionNote) {
        authService.requireRole(user, Role.LAB_ADMIN, Role.CUSTOMER_SERVICE, Role.QC_MANAGER);
        ShadeDispute dispute = getDispute(disputeId);
        if (dispute.getStatus() != ShadeDisputeStatus.RESHOT) {
            throw new BizException("请先完成自然光复拍再作出判定，当前状态: " + dispute.getStatus().getLabel());
        }
        dispute.setDecidedBy(user);
        dispute.setDecidedAt(LocalDateTime.now());
        dispute.setDecisionNote(decisionNote);

        if (!remake) {
            if (decisionNote == null || decisionNote.isBlank()) {
                throw new BizException("不重新制作时必须填写沟通结论 decisionNote");
            }
            dispute.setStatus(ShadeDisputeStatus.REJECTED);
            disputeRepo.save(dispute);
            return Views.shadeDispute(dispute);
        }

        // ---- 重新制作：原件状态、费用承担单独记录 ----
        if (targetShade == null || targetShade.isBlank()) {
            throw new BizException("重新制作必须填写新色号 targetShade");
        }
        if (originalCondition == null || originalCondition.isBlank()) {
            throw new BizException("重新制作必须记录原义齿状态 originalCondition");
        }
        if (disposition == null) {
            throw new BizException("重新制作必须记录原义齿去向 originalDisposition");
        }
        if (responsibility == null || responsibility == Responsibility.PENDING) {
            throw new BizException("重新制作必须判定费用承担方 costResponsibility（诊所/技工所/双方共担）");
        }
        if (remakeCost == null || remakeCost.signum() < 0) {
            throw new BizException("重新制作费用 remakeCost 必须为非负数");
        }
        if (newMaterialBatchId == null) {
            throw new BizException("重新制作必须指定新材料批次 newMaterialBatchId");
        }
        MaterialBatch newBatch = batchRepo.findById(newMaterialBatchId)
                .orElseThrow(() -> BizException.notFound("材料批次#" + newMaterialBatchId));

        dispute.setTargetShade(targetShade);
        dispute.setOriginalCondition(originalCondition);
        dispute.setOriginalDisposition(disposition);
        dispute.setCostResponsibility(responsibility);
        dispute.setRemakeCost(remakeCost);
        applyShares(dispute, responsibility, remakeCost);
        dispute.setNewMaterialBatch(newBatch);

        // 创建返工单（返工记录同步质检）：责任与费用同步，结算时按返工分摊规则处理
        ToothItem item = dispute.getToothItem();
        if (!reworkRepo.findByToothItemIdAndStatus(item.getId(), ReworkStatus.OPEN).isEmpty()) {
            throw new BizException("牙位 " + item.getToothPosition() + " 已有进行中的返工单，请先处理");
        }
        ReworkRecord rework = new ReworkRecord();
        rework.setOrder(dispute.getOrder());
        rework.setToothItem(item);
        rework.setReason("色号争议重新制作（牙位 " + item.getToothPosition() + "）：原色号 "
                + nullToDash(dispute.getOrder().getShade()) + " → 新色号 " + targetShade
                + "；原义齿去向: " + disposition.getLabel());
        rework.setResponsibility(responsibility);
        rework.setReworkCost(remakeCost);
        rework.setClinicShare(dispute.getClinicShare());
        rework.setLabShare(dispute.getLabShare());
        reworkRepo.save(rework);
        dispute.setRework(rework);

        // 牙位打回生产线，重新制作时使用新材料批次（在工序登记中体现）
        item.setStatus(ToothItemStatus.REWORKING);
        item.setCurrentStage(null);
        item.setUpdatedAt(LocalDateTime.now());
        itemRepo.save(item);

        DentureOrder order = dispute.getOrder();
        if (EnumSet.of(OrderStatus.COMPLETED, OrderStatus.TRY_IN, OrderStatus.DELIVERED,
                OrderStatus.SHIPPED, OrderStatus.QC_PASSED).contains(order.getStatus())) {
            order.setStatus(OrderStatus.IN_PRODUCTION);
            order.setUpdatedAt(LocalDateTime.now());
            orderRepo.save(order);
        }

        dispute.setStatus(ShadeDisputeStatus.REWORK_DECIDED);
        disputeRepo.save(dispute);
        return Views.shadeDispute(dispute);
    }

    /** 重做件重新通过质检时由生产工序回调：返工记录自动关闭，争议单同步质检结果。 */
    @Transactional
    public void onRemakeQcPassed(ToothItem item, Account qcOperator, StageRecord qcRecord) {
        for (ShadeDispute dispute : disputeRepo.findByToothItemIdOrderByIdAsc(item.getId())) {
            if (dispute.getStatus() == ShadeDisputeStatus.REWORK_DECIDED) {
                dispute.setRemakeQcBy(qcOperator);
                dispute.setRemakeQcAt(LocalDateTime.now());
                dispute.setRemakeQcNote("重做件已通过质检（工序记录#" + qcRecord.getId()
                        + "，质检人:" + qcOperator.getDisplayName() + "），返工单#"
                        + (dispute.getRework() == null ? "" : dispute.getRework().getId()) + "同步关闭");
                dispute.setStatus(ShadeDisputeStatus.REMAKE_QC_PASSED);
                disputeRepo.save(dispute);
            }
        }
    }

    /** 重做件患者试戴反馈回到同一牙位档案：密合则推进到待医生复核关闭。 */
    @Transactional
    public void onRemakeTryIn(ToothItem item, TryInRecord tryIn, TryInResult result) {
        for (ShadeDispute dispute : disputeRepo.findByToothItemIdOrderByIdAsc(item.getId())) {
            if (dispute.getStatus() == ShadeDisputeStatus.REMAKE_QC_PASSED) {
                dispute.setRemakeTryIn(tryIn);
                if (result == TryInResult.FIT) {
                    dispute.setStatus(ShadeDisputeStatus.REMAKE_FITTED);
                }
                disputeRepo.save(dispute);
            }
        }
    }

    /** 医生复核后关闭争议处理（患者接受/重做试戴完成均需医生复核）。 */
    @Transactional
    public Map<String, Object> doctorReview(Account user, Long disputeId, String note) {
        authService.requireRole(user, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN);
        ShadeDispute dispute = getDispute(disputeId);
        if (user.getClinic() == null
                || !user.getClinic().getId().equals(dispute.getOrder().getClinic().getId())) {
            throw BizException.forbidden("只能由本诊所医生复核本订单的色号争议");
        }
        if (dispute.getStatus() != ShadeDisputeStatus.REMAKE_FITTED
                && dispute.getStatus() != ShadeDisputeStatus.REJECTED) {
            throw new BizException("当前状态[" + dispute.getStatus().getLabel()
                    + "]不能医生复核关闭：重做件需完成试戴反馈，或已判定不重新制作");
        }
        dispute.setDoctorReviewNote(note == null || note.isBlank()
                ? (dispute.getStatus() == ShadeDisputeStatus.REMAKE_FITTED
                ? "医生复核：重做件颜色与邻牙一致，患者试戴满意，关闭争议"
                : "医生复核：经沟通患者接受原义齿，关闭争议") : note);
        dispute.setDoctorReviewedBy(user);
        dispute.setDoctorReviewedAt(LocalDateTime.now());
        dispute.setStatus(ShadeDisputeStatus.DOCTOR_REVIEWED);
        disputeRepo.save(dispute);
        return Views.shadeDispute(dispute);
    }

    /** 质检主管复核并归档质检结论。 */
    @Transactional
    public Map<String, Object> qcArchive(Account user, Long disputeId, ShadeQcConclusion conclusion, String note) {
        authService.requireRole(user, Role.QC_MANAGER);
        ShadeDispute dispute = getDispute(disputeId);
        if (dispute.getStatus() != ShadeDisputeStatus.DOCTOR_REVIEWED) {
            throw new BizException("医生复核关闭后才能由质检主管复核归档，当前状态: "
                    + dispute.getStatus().getLabel());
        }
        if (conclusion == null) {
            throw new BizException("请给出质检复核结论 qcConclusion");
        }
        dispute.setQcConclusion(conclusion);
        dispute.setQcConclusionNote(note);
        dispute.setQcReviewedBy(user);
        dispute.setArchivedAt(LocalDateTime.now());
        dispute.setStatus(ShadeDisputeStatus.ARCHIVED);
        disputeRepo.save(dispute);

        // 质检结论归档：关联色号争议异常事件同步关闭
        for (OrderException e : exceptionRepo.findByOrderIdOrderByIdAsc(dispute.getOrder().getId())) {
            if (e.getType() == ExceptionType.SHADE_DISPUTE
                    && e.getToothItem() != null
                    && e.getToothItem().getId().equals(dispute.getToothItem().getId())
                    && e.getStatus() != ExceptionStatus.RESOLVED) {
                e.setStatus(ExceptionStatus.RESOLVED);
                e.setHandledBy(user);
                e.setHandledAt(LocalDateTime.now());
                e.setResolution("质检主管复核归档：" + conclusion.getLabel()
                        + (note == null || note.isBlank() ? "" : "；" + note));
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

    /** 供牙位看板等服务内部聚合使用（在同一事务内，避免懒加载问题）。 */
    @Transactional(readOnly = true)
    public List<ShadeDispute> listByOrderInternal(Long orderId) {
        return disputeRepo.findByOrderIdOrderByIdAsc(orderId);
    }

    /** 争议处理工作台：未归档的色号争议（医生端/质检主管/客服同步可见）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listOpen(Account user) {
        List<ShadeDispute> all = disputeRepo.findByStatusInOrderByIdDesc(
                List.of(ShadeDisputeStatus.OPEN, ShadeDisputeStatus.EVIDENCE_COLLECTED, ShadeDisputeStatus.RESHOT,
                        ShadeDisputeStatus.REWORK_DECIDED, ShadeDisputeStatus.REMAKE_QC_PASSED,
                        ShadeDisputeStatus.REMAKE_FITTED, ShadeDisputeStatus.REJECTED,
                        ShadeDisputeStatus.DOCTOR_REVIEWED));
        return all.stream()
                .filter(d -> user.getClinic() == null
                        || user.getClinic().getId().equals(d.getOrder().getClinic().getId()))
                .map(Views::shadeDispute).collect(Collectors.toList());
    }

    // ---- 内部 ----

    private ShadeDispute getDispute(Long disputeId) {
        return disputeRepo.findById(disputeId)
                .orElseThrow(() -> BizException.notFound("色号争议单#" + disputeId));
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
            default -> throw new BizException("非法费用承担类型");
        }
    }

    private String fmt(LocalDateTime t) {
        return t == null ? "-" : t.format(FMT);
    }

    private String nullToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
