package com.amdocs.telecom.model.enums;

/**
 * Service catalogue from section 3 of the case study.
 */
public enum ServiceType implements DescribableEnum {

    MOBILE("MOB", "Mobile"),
    BROADBAND("BBD", "Broadband"),
    ENTERPRISE_CONNECTIVITY("ENC", "Enterprise Connectivity"),
    VPN("VPN", "Virtual Private Network"),
    CLOUD_CONNECTIVITY("CLC", "Cloud Connectivity");

    private final String code;
    private final String displayName;

    ServiceType(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Business grade services carry tighter operational expectations than
     * consumer grade ones.
     */
    public boolean isBusinessGrade() {
        return this == ENTERPRISE_CONNECTIVITY || this == VPN || this == CLOUD_CONNECTIVITY;
    }
}
