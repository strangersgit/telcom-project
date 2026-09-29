package com.amdocs.telecom.service.escalation;

import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.enums.EscalationLevel;

import java.util.Optional;

/**
 * What became of one ticket offered to the escalation engine.
 *
 * <p>The same shape, and for the same reason, as the assignment engine's
 * result: a person escalating one ticket by hand gets an exception when it
 * cannot be done, because they are waiting on an answer, while a sweep
 * turns each refusal into one of these so that one awkward ticket does not
 * abandon the queue behind it.</p>
 */
public final class EscalationOutcome implements Displayable {

    private final String ticketNumber;
    private final EscalationLevel fromLevel;
    private final EscalationLevel toLevel;
    private final boolean escalated;
    private final boolean automatic;
    private final String message;

    private EscalationOutcome(String ticketNumber, EscalationLevel fromLevel,
                              EscalationLevel toLevel, boolean escalated, boolean automatic,
                              String message) {
        this.ticketNumber = ticketNumber;
        this.fromLevel = fromLevel;
        this.toLevel = toLevel;
        this.escalated = escalated;
        this.automatic = automatic;
        this.message = message;
    }

    /**
     * A ticket that moved one rung.
     *
     * @param automatic whether the SLA engine decided, rather than a person
     */
    public static EscalationOutcome moved(String ticketNumber, EscalationLevel fromLevel,
                                          EscalationLevel toLevel, boolean automatic) {
        return new EscalationOutcome(ticketNumber, fromLevel, toLevel, true, automatic,
                "Raised from " + fromLevel.getDisplayName() + " to " + toLevel.getDisplayName());
    }

    /**
     * A ticket the sweep passed over, and why.
     */
    public static EscalationOutcome skipped(String ticketNumber, String reason) {
        return new EscalationOutcome(ticketNumber, null, null, false, true, reason);
    }

    public String getTicketNumber() {
        return ticketNumber;
    }

    /** Where the ticket was, empty when it did not move. */
    public Optional<EscalationLevel> findFromLevel() {
        return Optional.ofNullable(fromLevel);
    }

    /** Where it is now, empty when it did not move. */
    public Optional<EscalationLevel> findToLevel() {
        return Optional.ofNullable(toLevel);
    }

    public boolean isEscalated() {
        return escalated;
    }

    /** Whether the SLA engine decided this rather than a person. */
    public boolean isAutomatic() {
        return automatic;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-16s %-4s %s", ticketNumber, escalated ? "up" : "--", message);
    }

    @Override
    public String toString() {
        return ticketNumber + ": " + message;
    }
}
