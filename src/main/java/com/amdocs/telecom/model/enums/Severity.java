package com.amdocs.telecom.model.enums;

/**
 * Technical impact of a ticket or an incoming network event.
 *
 * <p>Severity describes how badly the network is hurt; {@link Priority}
 * describes how fast the operator has promised to respond. They are related
 * but set independently, which is why the case study lists both on a ticket.</p>
 */
public enum Severity implements DescribableEnum {

    INFO("S5", "Informational", 1),
    WARNING("S4", "Warning", 2),
    MINOR("S3", "Minor", 3),
    MAJOR("S2", "Major", 4),
    CRITICAL("S1", "Critical", 5);

    private final String code;
    private final String displayName;
    private final int weight;

    Severity(String code, String displayName, int weight) {
        this.code = code;
        this.displayName = displayName;
        this.weight = weight;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    public int getWeight() {
        return weight;
    }

    /**
     * Default priority for an automatically raised ticket when a network event
     * arrives with this severity.
     */
    public Priority toDefaultPriority() {
        switch (this) {
            case CRITICAL:
                return Priority.CRITICAL;
            case MAJOR:
                return Priority.HIGH;
            case MINOR:
                return Priority.MEDIUM;
            default:
                return Priority.LOW;
        }
    }
}
