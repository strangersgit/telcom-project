package com.amdocs.telecom.model.enums;

/**
 * Provisioning state of a subscribed telecom service.
 */
public enum ServiceStatus implements DescribableEnum {

    PENDING_ACTIVATION("PND", "Pending Activation"),
    ACTIVE("ACT", "Active"),
    SUSPENDED("SUS", "Suspended"),
    TERMINATED("TRM", "Terminated");

    private final String code;
    private final String displayName;

    ServiceStatus(String code, String displayName) {
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
     * A ticket may only be raised against a service that is live or degraded,
     * never against one that was never activated or has been terminated.
     */
    public boolean isTicketable() {
        return this == ACTIVE || this == SUSPENDED;
    }
}
