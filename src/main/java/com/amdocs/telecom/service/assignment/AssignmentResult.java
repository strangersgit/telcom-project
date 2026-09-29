package com.amdocs.telecom.service.assignment;

import com.amdocs.telecom.model.Displayable;

import java.util.Optional;

/**
 * What happened when one ticket was offered to the assignment engine.
 *
 * <p>A single assignment that a person asked for raises an exception when
 * it cannot be done, because they are waiting for an answer about that
 * ticket. A sweep over the queue cannot work that way: one ticket with no
 * suitable engineer must not abandon the twenty behind it. So the sweep
 * turns each refusal into one of these instead, and the caller gets a line
 * per ticket saying what became of it.</p>
 */
public final class AssignmentResult implements Displayable {

    private final String ticketNumber;
    private final String employeeCode;
    private final String previousEmployeeCode;
    private final MatchTier tier;
    private final boolean assigned;
    private final String message;

    private AssignmentResult(String ticketNumber, String employeeCode,
                             String previousEmployeeCode, MatchTier tier, boolean assigned,
                             String message) {
        this.ticketNumber = ticketNumber;
        this.employeeCode = employeeCode;
        this.previousEmployeeCode = previousEmployeeCode;
        this.tier = tier;
        this.assigned = assigned;
        this.message = message;
    }

    /**
     * A ticket that had nobody and now has somebody.
     *
     * @param tier how the engineer was found, or null when a person named
     *             them rather than the engine choosing
     */
    public static AssignmentResult assigned(String ticketNumber, String employeeCode,
                                            MatchTier tier) {
        return new AssignmentResult(ticketNumber, employeeCode, null, tier, true,
                "Assigned to " + employeeCode
                        + (tier == null ? "" : " (" + tier.getReason() + ")"));
    }

    /**
     * A ticket moved from one engineer to another.
     */
    public static AssignmentResult reassigned(String ticketNumber, String previousEmployeeCode,
                                              String employeeCode) {
        return new AssignmentResult(ticketNumber, employeeCode, previousEmployeeCode, null, true,
                "Moved from " + previousEmployeeCode + " to " + employeeCode);
    }

    /**
     * A ticket the sweep passed over, and why.
     */
    public static AssignmentResult skipped(String ticketNumber, String reason) {
        return new AssignmentResult(ticketNumber, null, null, null, false, reason);
    }

    public String getTicketNumber() {
        return ticketNumber;
    }

    /**
     * The engineer who now holds the ticket, empty when it was skipped.
     */
    public Optional<String> findEmployeeCode() {
        return Optional.ofNullable(employeeCode);
    }

    /**
     * The engineer who held it before, present only for a reassignment.
     */
    public Optional<String> findPreviousEmployeeCode() {
        return Optional.ofNullable(previousEmployeeCode);
    }

    /**
     * How the engine found the engineer, empty when a person chose them.
     */
    public Optional<MatchTier> findTier() {
        return Optional.ofNullable(tier);
    }

    public boolean isAssigned() {
        return assigned;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-16s %-4s %s", ticketNumber, assigned ? "ok" : "--", message);
    }

    @Override
    public String toString() {
        return ticketNumber + ": " + message;
    }
}
