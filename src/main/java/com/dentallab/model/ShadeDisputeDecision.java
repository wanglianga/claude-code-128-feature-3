package com.dentallab.model;

/** 色号争议判定结论。 */
public enum ShadeDisputeDecision {
    REMAKE("重新制作"),
    NO_REMAKE("维持原义齿");

    private final String label;

    ShadeDisputeDecision(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
