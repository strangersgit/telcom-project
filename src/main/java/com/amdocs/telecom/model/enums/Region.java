package com.amdocs.telecom.model.enums;

/**
 * Operating regions used to match an engineer to an incident and to drive the
 * regional incident report.
 */
public enum Region implements DescribableEnum {

    NORTH("RG01", "North"),
    SOUTH("RG02", "South"),
    EAST("RG03", "East"),
    WEST("RG04", "West"),
    CENTRAL("RG05", "Central");

    private final String code;
    private final String displayName;

    Region(String code, String displayName) {
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
