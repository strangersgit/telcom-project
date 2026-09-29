package com.amdocs.telecom.model.enums;

/**
 * Ticket lifecycle states from section 4 of the case study.
 *
 * <p>Which transitions are legal is enforced by the ticket service; this enum
 * only classifies the states themselves.</p>
 */
public enum TicketStatus implements DescribableEnum {

    OPEN("OPN", "Open"),
    ASSIGNED("ASG", "Assigned"),
    IN_PROGRESS("INP", "In Progress"),
    PENDING_CUSTOMER("PNC", "Pending Customer"),
    ESCALATED("ESC", "Escalated"),
    RESOLVED("RSV", "Resolved"),
    CLOSED("CLS", "Closed"),
    CANCELLED("CNL", "Cancelled");

    private final String code;
    private final String displayName;

    TicketStatus(String code, String displayName) {
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
     * Terminal states accept no further work and stop the SLA clock.
     */
    public boolean isTerminal() {
        return this == CLOSED || this == CANCELLED;
    }

    /**
     * Open states still count towards an engineer's active workload and remain
     * visible on the operational dashboards.
     */
    public boolean isActive() {
        return !isTerminal() && this != RESOLVED;
    }

    /**
     * Whether the delay is currently the operator's to answer for.
     *
     * <p>A ticket waiting on the customer is still running against its
     * deadline, because a promise that slides whenever the operator is
     * waiting for something is not one the customer can rely on. What this
     * distinguishes is who gets warned: the SLA monitor does not chase an
     * engineer over a ticket that is at risk only because the customer has
     * not replied. A deadline that has actually passed is reported either
     * way.</p>
     */
    public boolean isSlaClockRunning() {
        return isActive() && this != PENDING_CUSTOMER;
    }

    /**
     * States that no longer count as work in progress.
     *
     * <p>The complement of {@link #isActive()}, spelled out because the DAO
     * guards its updates with {@code status NOT IN ('RESOLVED', 'CLOSED',
     * 'CANCELLED')} and Java asking the same question should use the same
     * word for it.</p>
     */
    public boolean isFinished() {
        return !isActive();
    }

    /**
     * True once an engineer owns the ticket.
     */
    public boolean isAssigned() {
        return this != OPEN && this != CANCELLED;
    }
}
