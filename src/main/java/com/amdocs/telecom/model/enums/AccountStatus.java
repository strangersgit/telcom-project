package com.amdocs.telecom.model.enums;

/**
 * Lifecycle of a login account, including the locked state produced by the
 * failed attempt threshold.
 */
public enum AccountStatus implements DescribableEnum {

    ACTIVE("ACT", "Active"),
    LOCKED("LCK", "Locked"),
    DISABLED("DIS", "Disabled"),
    PENDING_ACTIVATION("PND", "Pending Activation");

    private final String code;
    private final String displayName;

    AccountStatus(String code, String displayName) {
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
     * Only an active account may complete a login.
     */
    public boolean canLogin() {
        return this == ACTIVE;
    }
}
