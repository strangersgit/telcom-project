package com.amdocs.telecom.model.enums;

/**
 * SLA standing of a ticket, as defined in section 8 of the case study.
 */
public enum SLAStatus implements DescribableEnum {

    WITHIN_SLA("SLA1", "Within SLA"),
    AT_RISK("SLA2", "At Risk"),
    BREACHED("SLA3", "Breached");

    private final String code;
    private final String displayName;

    SLAStatus(String code, String displayName) {
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
     * Both at risk and breached tickets are candidates for automatic
     * escalation by the SLA monitor.
     */
    public boolean requiresEscalation() {
        return this == AT_RISK || this == BREACHED;
    }
}
