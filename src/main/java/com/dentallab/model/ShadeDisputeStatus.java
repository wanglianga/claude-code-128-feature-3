package com.dentallab.model;

/** 色号争议处理状态。 */
public enum ShadeDisputeStatus {
    OPEN("待判定"),
    REMAKE_IN_PROGRESS("重新制作中"),
    CLOSED("医生复核关闭"),
    QC_REVIEWED("质检主管复核通过"),
    ARCHIVED("质检结论归档");

    private final String label;

    ShadeDisputeStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
