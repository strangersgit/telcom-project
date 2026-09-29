package com.amdocs.telecom.model.enums;

import java.util.Optional;

/**
 * The escalation ladder from section 9 of the case study:
 * engineer, team lead, network manager, operations manager.
 */
public enum EscalationLevel implements DescribableEnum {

    ENGINEER("L1", "Engineer", 1),
    TEAM_LEAD("L2", "Team Lead", 2),
    NETWORK_MANAGER("L3", "Network Manager", 3),
    OPERATIONS_MANAGER("L4", "Operations Manager", 4);

    private final String code;
    private final String displayName;
    private final int level;

    EscalationLevel(String code, String displayName, int level) {
        this.code = code;
        this.displayName = displayName;
        this.level = level;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    public int getLevel() {
        return level;
    }

    /**
     * The next rung up the ladder, or empty when already at the top.
     */
    public Optional<EscalationLevel> next() {
        EscalationLevel[] levels = values();
        int nextIndex = ordinal() + 1;
        return nextIndex < levels.length ? Optional.of(levels[nextIndex]) : Optional.<EscalationLevel>empty();
    }

    public boolean isTopLevel() {
        return this == OPERATIONS_MANAGER;
    }
}
