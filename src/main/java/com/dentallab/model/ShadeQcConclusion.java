package com.dentallab.model;

/** 质检主管对色号争议的最终复核结论（归档）。 */
public enum ShadeQcConclusion {
    COLOR_MATCHED("复核颜色与邻牙一致"),
    COLOR_DEVIATION_CONFIRMED("确认原义齿颜色偏差"),
    PATIENT_ACCEPTED("沟通后患者接受");

    private final String label;

    ShadeQcConclusion(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
