package com.dentallab.service;

import com.dentallab.config.BizException;
import com.dentallab.model.*;
import com.dentallab.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** 生产工序：按牙位推进 模型接收→扫描设计→医生确认→排牙→烧结→抛光→质检→配送。 */
@Service
public class ProductionService {

    /** 完成某工序后牙位进入的状态。 */
    private static final Map<StageType, ToothItemStatus> STATUS_AFTER_STAGE = Map.of(
            StageType.MODEL_RECEIVING, ToothItemStatus.DESIGNING,
            StageType.SCAN_DESIGN, ToothItemStatus.WAITING_CONFIRM,
            StageType.DOCTOR_CONFIRM, ToothItemStatus.CONFIRMED,
            StageType.TEETH_ARRANGEMENT, ToothItemStatus.SINTERING,
            StageType.SINTERING, ToothItemStatus.POLISHING,
            StageType.POLISHING, ToothItemStatus.QC,
            StageType.QC, ToothItemStatus.QC_PASSED,
            StageType.DELIVERY, ToothItemStatus.READY_TO_SHIP);

    private final DentureOrderRepo orderRepo;
    private final ToothItemRepo itemRepo;
    private final StageRecordRepo stageRepo;
    private final MaterialBatchRepo batchRepo;
    private final DesignAnnotationRepo annotationRepo;
    private final OrderExceptionRepo exceptionRepo;
    private final ReworkRecordRepo reworkRepo;
    private final ShipmentRepo shipmentRepo;
    private final TryInRecordRepo tryInRepo;
    private final ShadeDisputeRepo shadeDisputeRepo;
    private final AuthService authService;

    public ProductionService(DentureOrderRepo orderRepo, ToothItemRepo itemRepo, StageRecordRepo stageRepo,
                             MaterialBatchRepo batchRepo, DesignAnnotationRepo annotationRepo,
                             OrderExceptionRepo exceptionRepo, ReworkRecordRepo reworkRepo,
                             ShipmentRepo shipmentRepo, TryInRecordRepo tryInRepo,
                             ShadeDisputeRepo shadeDisputeRepo, AuthService authService) {
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.stageRepo = stageRepo;
        this.batchRepo = batchRepo;
        this.annotationRepo = annotationRepo;
        this.exceptionRepo = exceptionRepo;
        this.reworkRepo = reworkRepo;
        this.shipmentRepo = shipmentRepo;
        this.tryInRepo = tryInRepo;
        this.shadeDisputeRepo = shadeDisputeRepo;
        this.authService = authService;
    }

    /**
     * 记录一道工序。记录操作人、材料批次与用量（扣减库存）、照片、预计完成时间。
     * finish=true 时牙位推进到下一工序。
     */
    @Transactional
    public Map<String, Object> recordStage(Account operator, Long orderId, Long itemId, StageType stage,
                                           Long materialBatchId, BigDecimal materialQty, List<String> photos,
                                           String note, LocalDateTime estimatedFinishAt, boolean finish,
                                           String designSummary) {
        ToothItem item = getItem(orderId, itemId);
        DentureOrder order = item.getOrder();
        ensureOrderInProduction(operator, order);

        if (stage == StageType.DOCTOR_CONFIRM) {
            throw new BizException("医生确认请由诊所医生调用确认接口 /confirm");
        }
        checkStageRole(operator, stage);
        checkStageSequence(item, stage);

        StageRecord record = new StageRecord();
        record.setToothItem(item);
        record.setStage(stage);
        record.setOperator(operator);
        record.setNote(note);
        record.setEstimatedFinishAt(estimatedFinishAt);
        if (photos != null && !photos.isEmpty()) {
            record.setPhotos(String.join(",", photos));
        }
        if (materialBatchId != null) {
            if (materialQty == null || materialQty.signum() <= 0) {
                throw new BizException("使用材料批次时必须填写正数用量 materialQty");
            }
            MaterialBatch batch = batchRepo.findById(materialBatchId)
                    .orElseThrow(() -> BizException.notFound("材料批次#" + materialBatchId));
            if (batch.getRemainingQty().compareTo(materialQty) < 0) {
                throw new BizException("材料批次[" + batch.getBatchNo() + "]库存不足，剩余 "
                        + batch.getRemainingQty() + "，需要 " + materialQty);
            }
            batch.setRemainingQty(batch.getRemainingQty().subtract(materialQty));
            batchRepo.save(batch);
            record.setMaterialBatch(batch);
            record.setMaterialQty(materialQty);
        }
        if (designSummary != null && !designSummary.isBlank() && stage == StageType.SCAN_DESIGN) {
            item.setDesignSummary(designSummary);
        }
        if (finish) {
            record.setFinishedAt(LocalDateTime.now());
            advanceItem(item, stage);
        }
        stageRepo.save(record);
        item.setUpdatedAt(LocalDateTime.now());
        itemRepo.save(item);
        refreshOrderStatus(order);
        return Views.stage(record);
    }

    /** 诊所医生确认设计（写入 DOCTOR_CONFIRM 工序记录）。 */
    @Transactional
    public Map<String, Object> confirmDesign(Account doctor, Long orderId, Long itemId, String note,
                                             LocalDateTime estimatedFinishAt) {
        authService.requireRole(doctor, Role.CLINIC_DOCTOR);
        ToothItem item = getItem(orderId, itemId);
        DentureOrder order = item.getOrder();
        if (!order.getDoctor().getId().equals(doctor.getId())
                && (doctor.getClinic() == null || !doctor.getClinic().getId().equals(order.getClinic().getId()))) {
            throw BizException.forbidden("只能由本诊所医生确认该订单设计");
        }
        checkStageSequence(item, StageType.DOCTOR_CONFIRM);

        StageRecord record = new StageRecord();
        record.setToothItem(item);
        record.setStage(StageType.DOCTOR_CONFIRM);
        record.setOperator(doctor);
        record.setNote(note == null || note.isBlank() ? "医生确认设计方案" : note);
        record.setEstimatedFinishAt(estimatedFinishAt);
        record.setFinishedAt(LocalDateTime.now());
        stageRepo.save(record);
        advanceItem(item, StageType.DOCTOR_CONFIRM);
        item.setUpdatedAt(LocalDateTime.now());
        itemRepo.save(item);
        refreshOrderStatus(order);
        return Views.stage(record);
    }

    /** 医生在线批注；若设计已确认后再批注，自动登记“医生临时改设计”异常。 */
    @Transactional
    public Map<String, Object> annotate(Account doctor, Long orderId, Long itemId, String content) {
        authService.requireRole(doctor, Role.CLINIC_DOCTOR);
        ToothItem item = getItem(orderId, itemId);
        DentureOrder order = item.getOrder();
        if (doctor.getClinic() == null || !doctor.getClinic().getId().equals(order.getClinic().getId())) {
            throw BizException.forbidden("只能批注本诊所的订单");
        }
        DesignAnnotation annotation = new DesignAnnotation();
        annotation.setToothItem(item);
        annotation.setAuthor(doctor);
        annotation.setContent(content);
        annotationRepo.save(annotation);

        boolean designFrozen = item.getCurrentStage() != null
                && item.getCurrentStage().ordinal() > StageType.DOCTOR_CONFIRM.ordinal();
        if (designFrozen) {
            OrderException event = new OrderException();
            event.setOrder(order);
            event.setToothItem(item);
            event.setType(ExceptionType.DESIGN_CHANGE);
            event.setDescription("医生临时改设计（牙位 " + item.getToothPosition() + "）: " + content);
            event.setReportedBy(doctor);
            exceptionRepo.save(event);
        }
        return Views.annotation(annotation);
    }

    @Transactional
    public Map<String, Object> resolveAnnotation(Account user, Long annotationId) {
        authService.requireRole(user, Role.TECHNICIAN, Role.LAB_ADMIN, Role.LAB_RECEIVER);
        DesignAnnotation annotation = annotationRepo.findById(annotationId)
                .orElseThrow(() -> BizException.notFound("批注#" + annotationId));
        annotation.setResolved(true);
        annotationRepo.save(annotation);
        return Views.annotation(annotation);
    }

    /** 按牙位的生产看板：设计、制作、质检、配送、试戴与返工责任一目了然。 */
    @Transactional(readOnly = true)
    public Map<String, Object> toothBoard(Long orderId) {
        DentureOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> BizException.notFound("订单#" + orderId));
        Map<String, Object> board = new LinkedHashMap<>();
        board.put("order", Views.order(order));
        List<ReworkRecord> orderReworks = reworkRepo.findByOrderIdOrderByIdAsc(orderId);
        List<ShadeDispute> orderDisputes = shadeDisputeRepo.findByOrderIdOrderByIdAsc(orderId);
        List<Map<String, Object>> teeth = new ArrayList<>();
        for (ToothItem item : itemRepo.findByOrderIdOrderByIdAsc(orderId)) {
            Map<String, Object> tooth = Views.item(item);
            tooth.put("stages", stageRepo.findByToothItemIdOrderByIdAsc(item.getId()).stream()
                    .map(Views::stage).collect(Collectors.toList()));
            tooth.put("annotations", annotationRepo.findByToothItemIdOrderByIdAsc(item.getId()).stream()
                    .map(Views::annotation).collect(Collectors.toList()));
            tooth.put("reworks", orderReworks.stream()
                    .filter(r -> r.getToothItem().getId().equals(item.getId()))
                    .map(Views::rework).collect(Collectors.toList()));
            tooth.put("shadeDisputes", orderDisputes.stream()
                    .filter(d -> d.getToothItem().getId().equals(item.getId()))
                    .map(Views::shadeDispute).collect(Collectors.toList()));
            teeth.add(tooth);
        }
        board.put("teeth", teeth);
        board.put("shadeDisputes", orderDisputes.stream()
                .map(Views::shadeDispute).collect(Collectors.toList()));
        board.put("shipments", shipmentRepo.findByOrderIdOrderByIdDesc(orderId).stream()
                .map(Views::shipment).collect(Collectors.toList()));
        board.put("tryIns", tryInRepo.findByOrderIdOrderByIdAsc(orderId).stream()
                .map(Views::tryIn).collect(Collectors.toList()));
        return board;
    }

    // ---- 内部逻辑 ----

    private ToothItem getItem(Long orderId, Long itemId) {
        ToothItem item = itemRepo.findById(itemId).orElseThrow(() -> BizException.notFound("牙位明细#" + itemId));
        if (!item.getOrder().getId().equals(orderId)) {
            throw new BizException("牙位明细#" + itemId + "不属于订单#" + orderId);
        }
        return item;
    }

    private void ensureOrderInProduction(Account operator, DentureOrder order) {
        authService.requireRole(operator, Role.TECHNICIAN, Role.QC_INSPECTOR, Role.LAB_RECEIVER, Role.LAB_ADMIN);
        if (order.getStatus() == OrderStatus.SUBMITTED) {
            throw new BizException("技工所尚未接单，不能开始生产");
        }
        if (EnumSet.of(OrderStatus.COMPLETED, OrderStatus.CANCELLED).contains(order.getStatus())) {
            throw new BizException("订单已结束，不能继续登记工序");
        }
    }

    private void checkStageRole(Account operator, StageType stage) {
        switch (stage) {
            case QC -> authService.requireRole(operator, Role.QC_INSPECTOR, Role.LAB_ADMIN);
            case DELIVERY -> authService.requireRole(operator, Role.LAB_RECEIVER, Role.LAB_ADMIN, Role.DELIVERY_COURIER);
            case MODEL_RECEIVING -> authService.requireRole(operator, Role.LAB_RECEIVER, Role.LAB_ADMIN, Role.TECHNICIAN);
            default -> authService.requireRole(operator, Role.TECHNICIAN, Role.LAB_ADMIN);
        }
    }

    private void checkStageSequence(ToothItem item, StageType stage) {
        StageType expected = item.getCurrentStage() == null
                ? StageType.MODEL_RECEIVING
                : item.getCurrentStage().next();
        if (expected == null) {
            throw new BizException("牙位 " + item.getToothPosition() + " 已完成全部工序");
        }
        if (expected != stage) {
            throw new BizException("工序顺序错误：牙位 " + item.getToothPosition()
                    + " 当前应进行[" + expected.getLabel() + "]，不能登记[" + stage.getLabel() + "]");
        }
    }

    private void advanceItem(ToothItem item, StageType finishedStage) {
        item.setCurrentStage(finishedStage);
        item.setStatus(STATUS_AFTER_STAGE.get(finishedStage));
        // 返工中的牙位重新通过质检后，自动关闭未完成的返工单
        if (finishedStage == StageType.QC) {
            for (ReworkRecord rework : reworkRepo.findByToothItemIdAndStatus(item.getId(), ReworkStatus.OPEN)) {
                rework.setStatus(ReworkStatus.COMPLETED);
                rework.setCompletedAt(LocalDateTime.now());
                reworkRepo.save(rework);
            }
        }
    }

    private void refreshOrderStatus(DentureOrder order) {
        List<ToothItem> items = itemRepo.findByOrderIdOrderByIdAsc(order.getId());
        boolean anyStarted = items.stream().anyMatch(i -> i.getCurrentStage() != null);
        boolean allQcPassed = items.stream().allMatch(i ->
                EnumSet.of(ToothItemStatus.QC_PASSED, ToothItemStatus.READY_TO_SHIP,
                        ToothItemStatus.SHIPPED, ToothItemStatus.DELIVERED,
                        ToothItemStatus.TRY_IN_OK).contains(i.getStatus()));
        OrderStatus current = order.getStatus();
        if (allQcPassed && !items.isEmpty()
                && current != OrderStatus.QC_PASSED && current != OrderStatus.SHIPPED
                && current != OrderStatus.DELIVERED && current != OrderStatus.TRY_IN
                && current != OrderStatus.COMPLETED) {
            order.setStatus(OrderStatus.QC_PASSED);
        } else if (anyStarted && current == OrderStatus.ACCEPTED) {
            order.setStatus(OrderStatus.IN_PRODUCTION);
        } else if (anyStarted && current == OrderStatus.QC_PASSED && !allQcPassed) {
            // 返工把牙位打回生产线
            order.setStatus(OrderStatus.IN_PRODUCTION);
        }
        order.setUpdatedAt(LocalDateTime.now());
        orderRepo.save(order);
    }
}
