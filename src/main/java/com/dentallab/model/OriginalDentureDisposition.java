package com.dentallab.model;

/** 色号争议重新制作时，原义齿的去向。 */
public enum OriginalDentureDisposition {
    KEPT_IN_LAB("技工所留存"),
    RETURNED_TO_CLINIC("退回诊所"),
    DESTROYED("销毁"),
    KEPT_AS_SPARE("留作备用");

    private final String label;

    OriginalDentureDisposition(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
