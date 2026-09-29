package com.amdocs.telecom.model.enums;

/**
 * Engineer skill areas, taken from the worked example in section 7 of the case
 * study and extended to cover every incident category.
 */
public enum Specialization implements DescribableEnum {

    CORE_NETWORK("SP01", "Core Network"),
    RAN("SP02", "Radio Access Network"),
    BROADBAND("SP03", "Broadband"),
    IP_NETWORK("SP04", "IP Network"),
    TRANSMISSION("SP05", "Transmission"),
    ENTERPRISE_SERVICES("SP06", "Enterprise Services");

    private final String code;
    private final String displayName;

    Specialization(String code, String displayName) {
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
}
