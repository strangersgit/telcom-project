package com.amdocs.telecom.model.enums;

/**
 * Duty state of a network engineer. The assignment engine only considers
 * engineers who can currently accept work.
 */
public enum EngineerAvailability implements DescribableEnum {

    AVAILABLE("AV", "Available"),
    BUSY("BY", "Busy"),
    ON_LEAVE("OL", "On Leave"),
    OFF_SHIFT("OS", "Off Shift");

    private final String code;
    private final String displayName;

    EngineerAvailability(String code, String displayName) {
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
     * Whether the engineer can be handed a new ticket right now.
     */
    public boolean canAcceptWork() {
        return this == AVAILABLE;
    }
}
