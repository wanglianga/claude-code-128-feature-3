package com.dentallab.service;

import com.dentallab.config.BizException;
import com.dentallab.model.*;
import com.dentallab.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** 配送：按牙位发货、诊所签收；签收结果直接影响诊所试戴安排。 */
@Service
public class DeliveryService {

    private final ShipmentRepo shipmentRepo;
    private final DentureOrderRepo orderRepo;
    private final ToothItemRepo itemRepo;
    private final TryInRecordRepo tryInRepo;
    private final OrderExceptionRepo exceptionRepo;
    private final ReworkRecordRepo reworkRepo;
    private final AuthService authService;

    public DeliveryService(ShipmentRepo shipmentRepo, DentureOrderRepo orderRepo, ToothItemRepo itemRepo,
                           TryInRecordRepo tryInRepo, OrderExceptionRepo exceptionRepo,
                           ReworkRecordRepo reworkRepo, AuthService authService) {
        this.shipmentRepo = shipmentRepo;
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.tryInRepo = tryInRepo;
        this.exceptionRepo = exceptionRepo;
        this.reworkRepo = reworkRepo;
        this.authService = authService;
    }

    /**
     * 创建配送单并发货。itemIds 为空时发送全部已质检通过/待发货牙位，
     * 否则只发送指定牙位（支持返工后单个牙位重发）。
     */
    @Transactional
    public Map<String, Object> ship(Account user, Long orderId, List<Long> itemIds,
                                    LocalDateTime estimatedArrivalAt) {
        authService.requireRole(user, Role.LAB_RECEIVER, Role.LAB_ADMIN, Role.DELIVERY_COURIER);
        DentureOrder order = orderRepo.findById(orderId)
                .orElseThrow(() -> BizException.notFound("订单#" + orderId));
        List<ToothItem> items = itemRepo.findByOrderIdOrderByIdAsc(orderId);
        Set<Long> wanted = (itemIds == null || itemIds.isEmpty()) ? null : new HashSet<>(itemIds);
        List<ToothItem> shippable = items.stream()
                .filter(i -> wanted == null || wanted.contains(i.getId()))
                .filter(i -> EnumSet.of(ToothItemStatus.QC_PASSED, ToothItemStatus.READY_TO_SHIP)
                        .contains(i.getStatus()))
                .collect(Collectors.toList());
        if (shippable.isEmpty()) {
            throw new BizException("没有可发货的牙位（需质检通过/待发货状态）");
        }
        if (wanted != null && shippable.size() < wanted.size()) {
            throw new BizException("部分指定牙位不可发货，请检查牙位状态");
        }
        Shipment shipment = new Shipment();
        shipment.setOrder(order);
        shipment.setCourier(user.getRole() == Role.DELIVERY_COURIER ? user : null);
        shipment.setTrackingNo("SF" + UUID.randomUUID().toString().substring(0, 10).toUpperCase());
        shipment.setEstimatedArrivalAt(estimatedArrivalAt != null ? estimatedArrivalAt
                : LocalDateTime.now().plusDays(1));
        shipmentRepo.save(shipment);

        for (ToothItem item : shippable) {
            item.setStatus(ToothItemStatus.SHIPPED);
            item.setShipment(shipment);
            item.setUpdatedAt(LocalDateTime.now());
            itemRepo.save(item);
        }
        order.setStatus(OrderStatus.SHIPPED);
        order.setUpdatedAt(LocalDateTime.now());
        orderRepo.save(order);
        Map<String, Object> view = Views.shipment(shipment);
        view.put("shippedItemIds", shippable.stream().map(ToothItem::getId).collect(Collectors.toList()));
        return view;
    }

    /**
     * 诊所签收。签收结果决定试戴安排：
     * 完好→按预计试戴日期安排；延误→试戴顺延并登记配送延误异常；破损→本批次牙位返工。
     */
    @Transactional
    public Map<String, Object> sign(Account user, Long shipmentId, SignResult result, String signedByName,
                                    String signNote, String delayReason) {
        authService.requireRole(user, Role.CLINIC_ADMIN, Role.CLINIC_DOCTOR, Role.DELIVERY_COURIER);
        Shipment shipment = shipmentRepo.findById(shipmentId)
                .orElseThrow(() -> BizException.notFound("配送单#" + shipmentId));
        if (shipment.getStatus() == ShipmentStatus.DELIVERED) {
            throw new BizException("配送单已签收，不能重复签收");
        }
        DentureOrder order = shipment.getOrder();
        List<ToothItem> items = itemRepo.findByShipmentId(shipmentId);

        shipment.setStatus(ShipmentStatus.DELIVERED);
        shipment.setDeliveredAt(LocalDateTime.now());
        shipment.setSignResult(result);
        shipment.setSignedByName(signedByName);
        shipment.setSignNote(signNote);
        shipment.setDelayReason(delayReason);
        shipmentRepo.save(shipment);

        switch (result) {
            case GOOD -> {
                markItems(items, ToothItemStatus.DELIVERED);
                order.setStatus(OrderStatus.TRY_IN);
                scheduleTryIns(order, items, plannedTryInDate(order));
            }
            case LATE -> {
                markItems(items, ToothItemStatus.DELIVERED);
                order.setStatus(OrderStatus.TRY_IN);
                // 配送延误影响试戴安排：顺延一天并登记异常
                LocalDateTime postponed = plannedTryInDate(order).plusDays(1);
                scheduleTryIns(order, items, postponed);
                autoException(order, null, ExceptionType.DELIVERY_DELAY, user,
                        "配送延误签收，试戴安排顺延至 " + postponed.toLocalDate()
                                + (delayReason == null ? "" : "，原因: " + delayReason));
            }
            case DAMAGED -> {
                // 破损：本批次牙位返工，责任待判定
                for (ToothItem item : items) {
                    item.setStatus(ToothItemStatus.REWORKING);
                    item.setCurrentStage(null);
                    item.setUpdatedAt(LocalDateTime.now());
                    itemRepo.save(item);
                    ReworkRecord rework = new ReworkRecord();
                    rework.setOrder(order);
                    rework.setToothItem(item);
                    rework.setReasonType(ReworkReasonType.DELIVERY_DAMAGE);
                    rework.setReason("配送破损签收，需重新制作");
                    reworkRepo.save(rework);
                }
                order.setStatus(OrderStatus.IN_PRODUCTION);
                autoException(order, null, ExceptionType.DELIVERY_DAMAGE, user,
                        "配送破损签收，已自动创建 " + items.size() + " 个牙位的返工单，责任待判定");
            }
        }
        order.setUpdatedAt(LocalDateTime.now());
        orderRepo.save(order);
        return Views.shipment(shipment);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listByOrder(Long orderId) {
        return shipmentRepo.findByOrderIdOrderByIdDesc(orderId).stream()
                .map(Views::shipment).collect(Collectors.toList());
    }

    private void markItems(List<ToothItem> items, ToothItemStatus status) {
        for (ToothItem item : items) {
            item.setStatus(status);
            item.setUpdatedAt(LocalDateTime.now());
            itemRepo.save(item);
        }
    }

    private LocalDateTime plannedTryInDate(DentureOrder order) {
        LocalDate base = order.getExpectedTryInDate() != null
                && order.getExpectedTryInDate().isAfter(LocalDate.now())
                ? order.getExpectedTryInDate() : LocalDate.now();
        return base.atTime(10, 0);
    }

    /** 为每个签收牙位生成试戴安排。 */
    private void scheduleTryIns(DentureOrder order, List<ToothItem> items, LocalDateTime scheduledAt) {
        for (ToothItem item : items) {
            TryInRecord record = new TryInRecord();
            record.setOrder(order);
            record.setToothItem(item);
            record.setScheduledAt(scheduledAt);
            tryInRepo.save(record);
        }
    }

    private void autoException(DentureOrder order, ToothItem item, ExceptionType type,
                               Account reporter, String description) {
        OrderException event = new OrderException();
        event.setOrder(order);
        event.setToothItem(item);
        event.setType(type);
        event.setDescription(description);
        event.setReportedBy(reporter);
        exceptionRepo.save(event);
    }
}
