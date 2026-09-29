package com.dentallab.model;

/** 原义齿去向（色号争议重新制作时单独记录）。 */
public enum OriginalDentureDisposition {
    SCRAPPED("原件作废销毁"),
    RETURNED_TO_LAB("原件退回技工所"),
    KEPT_BY_PATIENT("患者保留原件");

    private final String label;

    OriginalDentureDisposition(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
