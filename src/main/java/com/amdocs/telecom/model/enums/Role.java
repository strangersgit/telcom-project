package com.amdocs.telecom.model.enums;

/**
 * The four actors described by the case study. Drives role based access
 * control and decides which dashboard a session lands on after login.
 */
public enum Role implements DescribableEnum {

    CUSTOMER("CUST", "Customer"),
    SERVICE_DESK("SDESK", "Service Desk Administrator"),
    NETWORK_ENGINEER("ENGR", "Network Engineer"),
    NETWORK_MANAGER("NMGR", "Network Manager");

    private final String code;
    private final String displayName;

    Role(String code, String displayName) {
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
     * Staff roles may act on tickets belonging to any customer.
     */
    public boolean isStaff() {
        return this != CUSTOMER;
    }

    /**
     * Roles permitted to view operational dashboards and reports.
     */
    public boolean canViewReports() {
        return this == SERVICE_DESK || this == NETWORK_MANAGER;
    }
}
