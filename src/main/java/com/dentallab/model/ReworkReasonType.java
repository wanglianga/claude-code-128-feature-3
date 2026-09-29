package com.dentallab.model;

/** 返工单发起原因分类，用于质检与医生端识别返工来源。 */
public enum ReworkReasonType {
    SHADE_DISPUTE("色号争议重做"),
    TRY_IN_MISFIT("试戴不密合"),
    DELIVERY_DAMAGE("配送破损"),
    OTHER("其他返工");

    private final String label;

    ReworkReasonType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
