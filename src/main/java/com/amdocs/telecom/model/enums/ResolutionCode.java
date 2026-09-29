package com.amdocs.telecom.model.enums;

/**
 * Closure codes from section 10 of the case study, recorded by the engineer
 * when an incident is resolved.
 */
public enum ResolutionCode implements DescribableEnum {

    HARDWARE_FAILURE("RC01", "Hardware Failure"),
    CONFIGURATION_ERROR("RC02", "Configuration Error"),
    NETWORK_CONGESTION("RC03", "Network Congestion"),
    SOFTWARE_FAILURE("RC04", "Software Failure"),
    FIBER_CUT("RC05", "Fiber Cut"),
    POWER_FAILURE("RC06", "Power Failure"),
    CUSTOMER_DEVICE("RC07", "Customer Device"),
    UNKNOWN("RC08", "Unknown");

    private final String code;
    private final String displayName;

    ResolutionCode(String code, String displayName) {
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
     * Causes that sit inside the operator's own network, and therefore count
     * against network quality in the incident category report.
     */
    public boolean isNetworkFault() {
        return this != CUSTOMER_DEVICE && this != UNKNOWN;
    }
}
