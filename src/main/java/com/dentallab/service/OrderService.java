package com.dentallab.service;

import com.dentallab.config.BizException;
import com.dentallab.model.*;
import com.dentallab.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** 订单：诊所下单、技工所接单、加急、口扫文件、订单详情聚合。 */
@Service
public class OrderService {

    private final DentureOrderRepo orderRepo;
    private final ToothItemRepo itemRepo;
    private final ScanFileRepo scanFileRepo;
    private final OrderExceptionRepo exceptionRepo;
    private final ReworkRecordRepo reworkRepo;
    private final ShipmentRepo shipmentRepo;
    private final TryInRecordRepo tryInRepo;
    private final SettlementRepo settlementRepo;
    private final ShadeDisputeService shadeDisputeService;
    private final AuthService authService;

    public OrderService(DentureOrderRepo orderRepo, ToothItemRepo itemRepo, ScanFileRepo scanFileRepo,
                        OrderExceptionRepo exceptionRepo, ReworkRecordRepo reworkRepo,
                        ShipmentRepo shipmentRepo, TryInRecordRepo tryInRepo,
                        SettlementRepo settlementRepo, ShadeDisputeService shadeDisputeService,
                        AuthService authService) {
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.scanFileRepo = scanFileRepo;
        this.exceptionRepo = exceptionRepo;
        this.reworkRepo = reworkRepo;
        this.shipmentRepo = shipmentRepo;
        this.tryInRepo = tryInRepo;
        this.settlementRepo = settlementRepo;
        this.shadeDisputeService = shadeDisputeService;
        this.authService = authService;
    }

    /** 诊所医生提交订单。 */
    @Transactional
    public Map<String, Object> create(Account doctor, String patientCode, ProsthesisType type,
                                      ImpressionMethod impressionMethod, List<String> toothPositions,
                                      String biteRelation, String shade, String doctorNotes,
                                      java.time.LocalDate expectedTryInDate, String materialRequirement,
                                      Integer difficulty) {
        authService.requireRole(doctor, Role.CLINIC_DOCTOR);
        if (doctor.getClinic() == null) {
            throw new BizException("当前账号未关联诊所，不能下单");
        }
        if (toothPositions == null || toothPositions.isEmpty()) {
            throw new BizException("至少需要一个牙位");
        }
        DentureOrder order = new DentureOrder();
        order.setOrderNo("TMP-" + UUID.randomUUID().toString().substring(0, 8));
        order.setClinic(doctor.getClinic());
        order.setDoctor(doctor);
        order.setPatientCode(patientCode);
        order.setProsthesisType(type);
        order.setImpressionMethod(impressionMethod);
        order.setBiteRelation(biteRelation);
        order.setShade(shade);
        order.setDoctorNotes(doctorNotes);
        order.setExpectedTryInDate(expectedTryInDate);
        order.setMaterialRequirement(materialRequirement);
        order.setDifficulty(difficulty == null ? 1 : difficulty);
        if (order.getDifficulty() < 1 || order.getDifficulty() > 5) {
            throw new BizException("订单难度 difficulty 必须在 1-5 之间");
        }
        order.setStatus(OrderStatus.SUBMITTED);
        orderRepo.save(order);
        order.setOrderNo("DL" + order.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%04d", order.getId()));
        orderRepo.save(order);

        for (String position : new LinkedHashSet<>(toothPositions)) {
            ToothItem item = new ToothItem();
            item.setOrder(order);
            item.setToothPosition(position);
            itemRepo.save(item);
        }
        return detail(order.getId());
    }

    /** 技工所接单。 */
    @Transactional
    public Map<String, Object> accept(Account labUser, Long orderId) {
        authService.requireRole(labUser, Role.LAB_RECEIVER, Role.LAB_ADMIN);
        DentureOrder order = getOrder(orderId);
        if (order.getStatus() != OrderStatus.SUBMITTED) {
            throw new BizException("只有[已提交]状态的订单可以接单，当前状态: " + order.getStatus().getLabel());
        }
        order.setStatus(OrderStatus.ACCEPTED);
        order.setUpdatedAt(java.time.LocalDateTime.now());
        orderRepo.save(order);
        return detail(orderId);
    }

    /** 患者加急：标记加急并留痕异常事件，结算时计加急费。 */
    @Transactional
    public Map<String, Object> rush(Account user, Long orderId, String reason) {
        authService.requireRole(user, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN, Role.CUSTOMER_SERVICE, Role.LAB_ADMIN);
        DentureOrder order = getOrder(orderId);
        if (EnumSet.of(OrderStatus.COMPLETED, OrderStatus.CANCELLED).contains(order.getStatus())) {
            throw new BizException("订单已结束，不能加急");
        }
        if (order.isRush()) {
            throw new BizException("订单已是加急状态");
        }
        order.setRush(true);
        order.setUpdatedAt(java.time.LocalDateTime.now());
        orderRepo.save(order);

        OrderException event = new OrderException();
        event.setOrder(order);
        event.setType(ExceptionType.RUSH_REQUEST);
        event.setDescription("患者加急: " + (reason == null || reason.isBlank() ? "患者要求提前试戴" : reason));
        event.setReportedBy(user);
        event.setStatus(ExceptionStatus.RESOLVED);
        event.setResolution("已启用加急生产，结算按价格配置收取加急费");
        event.setHandledAt(java.time.LocalDateTime.now());
        exceptionRepo.save(event);
        return detail(orderId);
    }

    /** 登记数字口扫文件。 */
    @Transactional
    public Map<String, Object> addScanFile(Account user, Long orderId, String fileName, String format,
                                           Long sizeBytes, String storagePath) {
        authService.requireRole(user, Role.CLINIC_DOCTOR, Role.CLINIC_ADMIN, Role.LAB_RECEIVER, Role.TECHNICIAN);
        DentureOrder order = getOrder(orderId);
        ScanFile file = new ScanFile();
        file.setOrder(order);
        file.setFileName(fileName);
        file.setFormat(format);
        file.setSizeBytes(sizeBytes);
        file.setStoragePath(storagePath);
        file.setUploadedBy(user);
        scanFileRepo.save(file);
        return Views.scanFile(file);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long orderId) {
        DentureOrder order = getOrder(orderId);
        Map<String, Object> m = Views.order(order);
        List<ToothItem> items = itemRepo.findByOrderIdOrderByIdAsc(orderId);
        m.put("toothItems", items.stream().map(Views::item).collect(Collectors.toList()));
        m.put("scanFiles", scanFileRepo.findByOrderIdOrderByIdAsc(orderId).stream()
                .map(Views::scanFile).collect(Collectors.toList()));
        m.put("exceptions", exceptionRepo.findByOrderIdOrderByIdAsc(orderId).stream()
                .map(Views::exception).collect(Collectors.toList()));
        m.put("reworks", reworkRepo.findByOrderIdOrderByIdAsc(orderId).stream()
                .map(Views::rework).collect(Collectors.toList()));
        m.put("shadeDisputes", shadeDisputeService.listByOrder(orderId));
        m.put("shipments", shipmentRepo.findByOrderIdOrderByIdDesc(orderId).stream()
                .map(Views::shipment).collect(Collectors.toList()));
        m.put("tryIns", tryInRepo.findByOrderIdOrderByIdAsc(orderId).stream()
                .map(Views::tryIn).collect(Collectors.toList()));
        settlementRepo.findByOrderId(orderId).ifPresent(s -> m.put("settlement", Views.settlement(s)));
        return m;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Account user, String status, Boolean rush) {
        List<DentureOrder> orders;
        boolean clinicSide = user.getRole() == Role.CLINIC_DOCTOR || user.getRole() == Role.CLINIC_ADMIN;
        if (status != null && !status.isBlank()) {
            OrderStatus st;
            try {
                st = OrderStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BizException("非法订单状态: " + status);
            }
            orders = clinicSide
                    ? orderRepo.findByClinicIdAndStatusOrderByIdDesc(user.getClinic().getId(), st)
                    : orderRepo.findByStatusOrderByIdDesc(st);
        } else {
            orders = clinicSide
                    ? orderRepo.findByClinicIdOrderByIdDesc(user.getClinic().getId())
                    : orderRepo.findAllByOrderByIdDesc();
        }
        return orders.stream()
                .filter(o -> rush == null || o.isRush() == rush)
                .map(o -> {
                    Map<String, Object> m = Views.order(o);
                    m.put("toothCount", itemRepo.findByOrderIdOrderByIdAsc(o.getId()).size());
                    return m;
                }).collect(Collectors.toList());
    }

    public DentureOrder getOrder(Long orderId) {
        return orderRepo.findById(orderId).orElseThrow(() -> BizException.notFound("订单#" + orderId));
    }
}
