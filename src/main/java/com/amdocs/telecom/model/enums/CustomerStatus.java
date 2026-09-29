package com.amdocs.telecom.model.enums;

/**
 * Account standing of a customer. Only an active customer may raise a ticket.
 */
public enum CustomerStatus implements DescribableEnum {

    ACTIVE("ACT", "Active"),
    INACTIVE("INA", "Inactive"),
    SUSPENDED("SUS", "Suspended"),
    CLOSED("CLS", "Closed");

    private final String code;
    private final String displayName;

    CustomerStatus(String code, String displayName) {
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

    public boolean canRaiseTicket() {
        return this == ACTIVE;
    }
}
