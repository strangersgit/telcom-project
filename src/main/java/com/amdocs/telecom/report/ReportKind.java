package com.amdocs.telecom.report;

import com.amdocs.telecom.model.enums.DescribableEnum;

/**
 * The seven reports section 18 asks for, in the order it lists them.
 *
 * <p>An enum rather than free strings so a console can offer the list, a
 * caller cannot ask for one that does not exist, and
 * {@link ReportGenerator} can turn a request into work without a chain of
 * string comparisons.</p>
 *
 * <p>Each carries whether it needs a date window. Only the volume report
 * does, and saying so here means the console can prompt for dates on that
 * one and not on the others without knowing anything else about it.</p>
 */
public enum ReportKind implements DescribableEnum {

    TICKET_VOLUME("RPT1", "Ticket Volume Report",
            "Tickets raised per day across a window", true),
    SLA_COMPLIANCE("RPT2", "SLA Compliance Report",
            "Met against breached, per priority band", false),
    ENGINEER_PERFORMANCE("RPT3", "Engineer Performance Report",
            "What each engineer is carrying and what they have finished", false),
    INCIDENT_CATEGORY("RPT4", "Incident Category Report",
            "Volume and resolution time per kind of fault", false),
    REGIONAL_INCIDENT("RPT5", "Regional Incident Report",
            "Where the trouble is, by the reporting customer's region", false),
    AVERAGE_RESOLUTION("RPT6", "Average Resolution Report",
            "How long resolution takes, overall and broken down", false),
    CRITICAL_INCIDENT("RPT7", "Critical Incident Report",
            "Every critical ticket, most urgent first", false);

    private final String code;
    private final String displayName;
    private final String summary;
    private final boolean dated;

    ReportKind(String code, String displayName, String summary, boolean dated) {
        this.code = code;
        this.displayName = displayName;
        this.summary = summary;
        this.dated = dated;
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
     * What the report answers, for a console offering the list.
     */
    public String getSummary() {
        return summary;
    }

    /**
     * Whether this report is about a period rather than about the present.
     */
    public boolean isDated() {
        return dated;
    }

    /**
     * A stem for the exported file, lower case and safe on every
     * filesystem.
     */
    public String getFileStem() {
        return code.toLowerCase() + "_" + name().toLowerCase();
    }

    @Override
    public String toString() {
        return displayName;
    }
}
