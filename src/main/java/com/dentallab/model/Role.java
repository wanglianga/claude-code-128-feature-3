package com.dentallab.model;

/** 系统角色：诊所医生、诊所管理员、技工所前台、技工所管理员、技工、质检员、配送员、客服、财务。 */
public enum Role {
    CLINIC_DOCTOR("诊所医生"),
    CLINIC_ADMIN("诊所管理员"),
    LAB_RECEIVER("技工所前台"),
    LAB_ADMIN("技工所管理员"),
    TECHNICIAN("技工"),
    QC_INSPECTOR("质检员"),
    QC_MANAGER("质检主管"),
    DELIVERY_COURIER("配送员"),
    CUSTOMER_SERVICE("客服"),
    FINANCE("财务");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
