package com.amdocs.telecom.model.enums;

/**
 * Incident categories listed in section 6 of the case study.
 *
 * <p>Each category names the engineer specialization that normally handles it,
 * which the assignment engine uses as its first filter.</p>
 */
public enum IncidentCategory implements DescribableEnum {

    NETWORK_OUTAGE("CAT01", "Network Outage", Specialization.CORE_NETWORK),
    CALL_DROP("CAT02", "Call Drop", Specialization.RAN),
    SLOW_DATA("CAT03", "Slow Data", Specialization.RAN),
    NO_CONNECTIVITY("CAT04", "No Connectivity", Specialization.IP_NETWORK),
    SIM_ISSUE("CAT05", "SIM Issue", Specialization.CORE_NETWORK),
    BILLING("CAT06", "Billing", Specialization.ENTERPRISE_SERVICES),
    BROADBAND("CAT07", "Broadband", Specialization.BROADBAND),
    ROAMING("CAT08", "Roaming", Specialization.CORE_NETWORK),
    ENTERPRISE_LINK("CAT09", "Enterprise Link", Specialization.TRANSMISSION),
    OTHER("CAT10", "Other", Specialization.ENTERPRISE_SERVICES);

    private final String code;
    private final String displayName;
    private final Specialization preferredSpecialization;

    IncidentCategory(String code, String displayName, Specialization preferredSpecialization) {
        this.code = code;
        this.displayName = displayName;
        this.preferredSpecialization = preferredSpecialization;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    public Specialization getPreferredSpecialization() {
        return preferredSpecialization;
    }

    /**
     * Categories that normally take the whole service down rather than
     * degrading it.
     */
    public boolean isServiceAffecting() {
        return this == NETWORK_OUTAGE || this == NO_CONNECTIVITY || this == ENTERPRISE_LINK;
    }
}
