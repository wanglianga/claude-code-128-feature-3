package com.dentallab.model;

/**
 * 色号争议处理状态。
 * 发起 → 调取证据 → 自然光复拍 → 重新制作判定 → 重做质检 → 重做试戴 → 医生复核关闭 → 质检主管复核归档。
 */
public enum ShadeDisputeStatus {
    OPEN("待调取证据"),
    EVIDENCE_COLLECTED("已调取证据"),
    RESHOT("已自然光复拍"),
    REWORK_DECIDED("已决定重新制作"),
    REMAKE_QC_PASSED("重做件已通过质检"),
    REMAKE_FITTED("重做件已试戴"),
    REJECTED("不重新制作"),
    DOCTOR_REVIEWED("医生已复核关闭"),
    ARCHIVED("质检结论已归档");

    private final String label;

    ShadeDisputeStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
