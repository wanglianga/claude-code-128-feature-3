package com.dentallab.service;

import com.dentallab.model.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 实体 → 响应 Map 的统一转换，避免暴露 JPA 懒加载代理。 */
public final class Views {

    private Views() {
    }

    public static Map<String, Object> account(Account a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("username", a.getUsername());
        m.put("displayName", a.getDisplayName());
        m.put("role", a.getRole().name());
        m.put("roleLabel", a.getRole().getLabel());
        if (a.getClinic() != null) {
            m.put("clinicId", a.getClinic().getId());
            m.put("clinicName", a.getClinic().getName());
        }
        return m;
    }

    public static Map<String, Object> clinic(Clinic c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("contactPerson", c.getContactPerson());
        m.put("phone", c.getPhone());
        m.put("address", c.getAddress());
        return m;
    }

    public static Map<String, Object> order(DentureOrder o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("orderNo", o.getOrderNo());
        m.put("clinicId", o.getClinic().getId());
        m.put("clinicName", o.getClinic().getName());
        m.put("doctorId", o.getDoctor().getId());
        m.put("doctorName", o.getDoctor().getDisplayName());
        m.put("patientCode", o.getPatientCode());
        m.put("prosthesisType", o.getProsthesisType().name());
        m.put("prosthesisTypeLabel", o.getProsthesisType().getLabel());
        m.put("impressionMethod", o.getImpressionMethod().name());
        m.put("impressionMethodLabel", o.getImpressionMethod().getLabel());
        m.put("biteRelation", o.getBiteRelation());
        m.put("shade", o.getShade());
        m.put("doctorNotes", o.getDoctorNotes());
        m.put("expectedTryInDate", o.getExpectedTryInDate());
        m.put("materialRequirement", o.getMaterialRequirement());
        m.put("difficulty", o.getDifficulty());
        m.put("rush", o.isRush());
        m.put("status", o.getStatus().name());
        m.put("statusLabel", o.getStatus().getLabel());
        m.put("createdAt", o.getCreatedAt());
        m.put("completedAt", o.getCompletedAt());
        return m;
    }

    public static Map<String, Object> item(ToothItem t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("orderId", t.getOrder().getId());
        m.put("toothPosition", t.getToothPosition());
        m.put("status", t.getStatus().name());
        m.put("statusLabel", t.getStatus().getLabel());
        m.put("currentStage", t.getCurrentStage() == null ? null : t.getCurrentStage().name());
        m.put("currentStageLabel", t.getCurrentStage() == null ? null : t.getCurrentStage().getLabel());
        m.put("designSummary", t.getDesignSummary());
        m.put("shipmentId", t.getShipment() == null ? null : t.getShipment().getId());
        return m;
    }

    public static Map<String, Object> stage(StageRecord s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("toothItemId", s.getToothItem().getId());
        m.put("stage", s.getStage().name());
        m.put("stageLabel", s.getStage().getLabel());
        m.put("operatorId", s.getOperator().getId());
        m.put("operatorName", s.getOperator().getDisplayName());
        m.put("operatorRole", s.getOperator().getRole().name());
        if (s.getMaterialBatch() != null) {
            m.put("materialBatchId", s.getMaterialBatch().getId());
            m.put("materialBatchNo", s.getMaterialBatch().getBatchNo());
            m.put("materialName", s.getMaterialBatch().getMaterial().getName());
            m.put("materialQty", s.getMaterialQty());
        }
        m.put("photos", s.getPhotos());
        m.put("note", s.getNote());
        m.put("estimatedFinishAt", s.getEstimatedFinishAt());
        m.put("startedAt", s.getStartedAt());
        m.put("finishedAt", s.getFinishedAt());
        return m;
    }

    public static Map<String, Object> scanFile(ScanFile f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("orderId", f.getOrder().getId());
        m.put("fileName", f.getFileName());
        m.put("format", f.getFormat());
        m.put("sizeBytes", f.getSizeBytes());
        m.put("storagePath", f.getStoragePath());
        m.put("uploadedBy", f.getUploadedBy() == null ? null : f.getUploadedBy().getDisplayName());
        m.put("uploadedAt", f.getUploadedAt());
        return m;
    }

    public static Map<String, Object> annotation(DesignAnnotation a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("toothItemId", a.getToothItem().getId());
        m.put("authorId", a.getAuthor().getId());
        m.put("authorName", a.getAuthor().getDisplayName());
        m.put("content", a.getContent());
        m.put("resolved", a.isResolved());
        m.put("createdAt", a.getCreatedAt());
        return m;
    }

    public static Map<String, Object> exception(OrderException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("orderId", e.getOrder().getId());
        m.put("orderNo", e.getOrder().getOrderNo());
        m.put("toothItemId", e.getToothItem() == null ? null : e.getToothItem().getId());
        m.put("toothPosition", e.getToothItem() == null ? null : e.getToothItem().getToothPosition());
        m.put("type", e.getType().name());
        m.put("typeLabel", e.getType().getLabel());
        m.put("description", e.getDescription());
        m.put("reportedBy", e.getReportedBy().getDisplayName());
        m.put("reportedByRole", e.getReportedBy().getRole().name());
        m.put("status", e.getStatus().name());
        m.put("statusLabel", e.getStatus().getLabel());
        m.put("handledBy", e.getHandledBy() == null ? null : e.getHandledBy().getDisplayName());
        m.put("resolution", e.getResolution());
        m.put("createdAt", e.getCreatedAt());
        m.put("handledAt", e.getHandledAt());
        return m;
    }

    public static Map<String, Object> rework(ReworkRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("orderId", r.getOrder().getId());
        m.put("orderNo", r.getOrder().getOrderNo());
        m.put("toothItemId", r.getToothItem().getId());
        m.put("toothPosition", r.getToothItem().getToothPosition());
        m.put("reason", r.getReason());
        m.put("responsibility", r.getResponsibility().name());
        m.put("responsibilityLabel", r.getResponsibility().getLabel());
        m.put("status", r.getStatus().name());
        m.put("statusLabel", r.getStatus().getLabel());
        m.put("reworkCost", r.getReworkCost());
        m.put("clinicShare", r.getClinicShare());
        m.put("labShare", r.getLabShare());
        m.put("clinicReviewed", r.isClinicReviewed());
        m.put("reviewedBy", r.getReviewedBy() == null ? null : r.getReviewedBy().getDisplayName());
        m.put("reviewedAt", r.getReviewedAt());
        m.put("createdAt", r.getCreatedAt());
        m.put("completedAt", r.getCompletedAt());
        return m;
    }

    public static Map<String, Object> shadeDispute(ShadeDispute d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("orderId", d.getOrder().getId());
        m.put("orderNo", d.getOrder().getOrderNo());
        m.put("toothItemId", d.getToothItem().getId());
        m.put("toothPosition", d.getToothItem().getToothPosition());
        m.put("patientClaim", d.getPatientClaim());
        m.put("claimedShade", d.getClaimedShade());
        m.put("shadePhotos", splitPhotos(d.getShadePhotos()));
        m.put("doctorConfirmRecord", d.getDoctorConfirmRecord());
        m.put("materialBatchRecord", d.getMaterialBatchRecord());
        m.put("collectedBy", d.getCollectedBy() == null ? null : d.getCollectedBy().getDisplayName());
        m.put("collectedAt", d.getCollectedAt());
        m.put("naturalLightPhotos", splitPhotos(d.getNaturalLightPhotos()));
        m.put("reshootNote", d.getReshootNote());
        m.put("reshotBy", d.getReshotBy() == null ? null : d.getReshotBy().getDisplayName());
        m.put("reshotAt", d.getReshotAt());
        m.put("status", d.getStatus().name());
        m.put("statusLabel", d.getStatus().getLabel());
        m.put("targetShade", d.getTargetShade());
        m.put("originalCondition", d.getOriginalCondition());
        m.put("originalDisposition", d.getOriginalDisposition() == null ? null : d.getOriginalDisposition().name());
        m.put("originalDispositionLabel",
                d.getOriginalDisposition() == null ? null : d.getOriginalDisposition().getLabel());
        m.put("costResponsibility", d.getCostResponsibility() == null ? null : d.getCostResponsibility().name());
        m.put("costResponsibilityLabel",
                d.getCostResponsibility() == null ? null : d.getCostResponsibility().getLabel());
        m.put("remakeCost", d.getRemakeCost());
        m.put("clinicShare", d.getClinicShare());
        m.put("labShare", d.getLabShare());
        if (d.getNewMaterialBatch() != null) {
            m.put("newMaterialBatchId", d.getNewMaterialBatch().getId());
            m.put("newMaterialBatchNo", d.getNewMaterialBatch().getBatchNo());
            m.put("newMaterialName", d.getNewMaterialBatch().getMaterial().getName());
        }
        m.put("reworkId", d.getRework() == null ? null : d.getRework().getId());
        m.put("decidedBy", d.getDecidedBy() == null ? null : d.getDecidedBy().getDisplayName());
        m.put("decidedAt", d.getDecidedAt());
        m.put("remakeQcNote", d.getRemakeQcNote());
        m.put("remakeQcBy", d.getRemakeQcBy() == null ? null : d.getRemakeQcBy().getDisplayName());
        m.put("remakeQcAt", d.getRemakeQcAt());
        m.put("remakeTryInId", d.getRemakeTryIn() == null ? null : d.getRemakeTryIn().getId());
        m.put("doctorReviewNote", d.getDoctorReviewNote());
        m.put("doctorReviewedBy", d.getDoctorReviewedBy() == null ? null : d.getDoctorReviewedBy().getDisplayName());
        m.put("doctorReviewedAt", d.getDoctorReviewedAt());
        m.put("qcConclusion", d.getQcConclusion() == null ? null : d.getQcConclusion().name());
        m.put("qcConclusionLabel", d.getQcConclusion() == null ? null : d.getQcConclusion().getLabel());
        m.put("qcConclusionNote", d.getQcConclusionNote());
        m.put("qcReviewedBy", d.getQcReviewedBy() == null ? null : d.getQcReviewedBy().getDisplayName());
        m.put("archivedAt", d.getArchivedAt());
        m.put("createdAt", d.getCreatedAt());
        return m;
    }

    private static List<String> splitPhotos(String photos) {
        if (photos == null || photos.isBlank()) {
            return List.of();
        }
        return Arrays.stream(photos.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public static Map<String, Object> material(Material mat, java.math.BigDecimal remaining) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", mat.getId());
        m.put("code", mat.getCode());
        m.put("name", mat.getName());
        m.put("unit", mat.getUnit());
        m.put("unitPrice", mat.getUnitPrice());
        m.put("remainingQty", remaining);
        return m;
    }

    public static Map<String, Object> batch(MaterialBatch b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("materialId", b.getMaterial().getId());
        m.put("materialName", b.getMaterial().getName());
        m.put("batchNo", b.getBatchNo());
        m.put("totalQty", b.getTotalQty());
        m.put("remainingQty", b.getRemainingQty());
        m.put("expiryDate", b.getExpiryDate());
        return m;
    }

    public static Map<String, Object> shipment(Shipment s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("orderId", s.getOrder().getId());
        m.put("orderNo", s.getOrder().getOrderNo());
        m.put("courier", s.getCourier() == null ? null : s.getCourier().getDisplayName());
        m.put("trackingNo", s.getTrackingNo());
        m.put("status", s.getStatus().name());
        m.put("statusLabel", s.getStatus().getLabel());
        m.put("shippedAt", s.getShippedAt());
        m.put("estimatedArrivalAt", s.getEstimatedArrivalAt());
        m.put("deliveredAt", s.getDeliveredAt());
        m.put("signedByName", s.getSignedByName());
        m.put("signResult", s.getSignResult() == null ? null : s.getSignResult().name());
        m.put("signResultLabel", s.getSignResult() == null ? null : s.getSignResult().getLabel());
        m.put("signNote", s.getSignNote());
        m.put("delayReason", s.getDelayReason());
        return m;
    }

    public static Map<String, Object> tryIn(TryInRecord t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("orderId", t.getOrder().getId());
        m.put("toothItemId", t.getToothItem() == null ? null : t.getToothItem().getId());
        m.put("toothPosition", t.getToothItem() == null ? null : t.getToothItem().getToothPosition());
        m.put("scheduledAt", t.getScheduledAt());
        m.put("actualAt", t.getActualAt());
        m.put("result", t.getResult() == null ? null : t.getResult().name());
        m.put("resultLabel", t.getResult() == null ? null : t.getResult().getLabel());
        m.put("feedback", t.getFeedback());
        m.put("experienceScore", t.getExperienceScore());
        return m;
    }

    public static Map<String, Object> settlement(Settlement s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("orderId", s.getOrder().getId());
        m.put("orderNo", s.getOrder().getOrderNo());
        m.put("baseAmount", s.getBaseAmount());
        m.put("difficultyMultiplier", s.getDifficultyMultiplier());
        m.put("rushFee", s.getRushFee());
        m.put("reworkClinicShare", s.getReworkClinicShare());
        m.put("reworkLabDeduction", s.getReworkLabDeduction());
        m.put("deliveryPenalty", s.getDeliveryPenalty());
        m.put("totalAmount", s.getTotalAmount());
        m.put("detail", s.getDetail());
        m.put("generatedAt", s.getGeneratedAt());
        return m;
    }

    public static Map<String, Object> statement(MonthlyStatement s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("clinicId", s.getClinic().getId());
        m.put("clinicName", s.getClinic().getName());
        m.put("yearMonth", s.getYearMonth());
        m.put("orderCount", s.getOrderCount());
        m.put("totalAmount", s.getTotalAmount());
        m.put("unreviewedReworkCount", s.getUnreviewedReworkCount());
        m.put("status", s.getStatus().name());
        m.put("statusLabel", s.getStatus().getLabel());
        m.put("generatedAt", s.getGeneratedAt());
        m.put("confirmedAt", s.getConfirmedAt());
        return m;
    }
}
