package com.amdocs.telecom.model.enums;

import java.util.Comparator;

/**
 * Ticket priority from section 4 of the case study.
 *
 * <p>The weight drives two things: ordering in the escalation
 * {@link java.util.PriorityQueue} built in the escalation phase, and the
 * default SLA band looked up from the SLA configuration table.</p>
 */
public enum Priority implements DescribableEnum {

    LOW("P4", "Low", 1),
    MEDIUM("P3", "Medium", 2),
    HIGH("P2", "High", 3),
    CRITICAL("P1", "Critical", 4);

    private final String code;
    private final String displayName;
    private final int weight;

    Priority(String code, String displayName, int weight) {
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
     * Orders the most urgent priority first, which is what the escalation
     * queue needs.
     */
    public static Comparator<Priority> mostUrgentFirst() {
        return Comparator.comparingInt(Priority::getWeight).reversed();
    }
}
