package com.amdocs.telecom.model.enums;

/**
 * Customer segments defined in section 3 of the case study.
 *
 * <p>The tier weight lets enterprise incidents outrank consumer incidents when
 * two tickets share the same priority in the escalation queue.</p>
 */
public enum CustomerType implements DescribableEnum {

    CONSUMER("CONS", "Consumer", 1),
    SME("SME", "Small and Medium Enterprise", 2),
    ENTERPRISE("ENTP", "Enterprise", 3);

    private final String code;
    private final String displayName;
    private final int tierWeight;

    CustomerType(String code, String displayName, int tierWeight) {
        this.code = code;
        this.displayName = displayName;
        this.tierWeight = tierWeight;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    public int getTierWeight() {
        return tierWeight;
    }
}
