package com.amdocs.telecom.service.event;

import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.enums.EventStatus;

import java.util.Optional;

/**
 * What became of one network alarm the consumer picked up.
 *
 * <p>Section 11 says a background thread should process events and
 * "potentially create trouble tickets automatically". Potentially is the
 * operative word: most of what a network emits is not worth a ticket, and
 * some of what is worth one belongs on a ticket that already exists. So a
 * consumer reports which of four things happened rather than returning a
 * ticket or nothing.</p>
 */
public final class EventOutcome implements Displayable {

    private final String eventReference;
    private final EventStatus status;
    private final Long ticketId;
    private final String ticketNumber;
    private final boolean ticketRaised;
    private final String message;

    private EventOutcome(String eventReference, EventStatus status, Long ticketId,
                         String ticketNumber, boolean ticketRaised, String message) {
        this.eventReference = eventReference;
        this.status = status;
        this.ticketId = ticketId;
        this.ticketNumber = ticketNumber;
        this.ticketRaised = ticketRaised;
        this.message = message;
    }

    /** An alarm that opened a new ticket. */
    public static EventOutcome ticketRaised(String eventReference, Long ticketId,
                                            String ticketNumber) {
        return new EventOutcome(eventReference, EventStatus.TICKET_CREATED, ticketId,
                ticketNumber, true, "Raised " + ticketNumber);
    }

    /**
     * An alarm folded into a ticket that was already open for the same fault.
     *
     * <p>Still {@code TICKET_CREATED} in the table, because the event did
     * end up on a ticket, and the row records which one. A separate status
     * for correlation would be a distinction the schema was not built for.</p>
     */
    public static EventOutcome correlated(String eventReference, Long ticketId,
                                          String ticketNumber) {
        return new EventOutcome(eventReference, EventStatus.TICKET_CREATED, ticketId,
                ticketNumber, false, "Added to the open ticket " + ticketNumber);
    }

    /** An alarm not worth a ticket, and why. */
    public static EventOutcome ignored(String eventReference, String reason) {
        return new EventOutcome(eventReference, EventStatus.IGNORED, null, null, false, reason);
    }

    /** An alarm whose processing threw. */
    public static EventOutcome failed(String eventReference, String reason) {
        return new EventOutcome(eventReference, EventStatus.FAILED, null, null, false, reason);
    }

    /**
     * An alarm another consumer had already taken. Nothing was written and
     * nothing is wrong: this is the losing side of the claim.
     */
    public static EventOutcome alreadyClaimed(String eventReference) {
        return new EventOutcome(eventReference, null, null, null, false,
                "Already claimed by another consumer");
    }

    public String getEventReference() {
        return eventReference;
    }

    /**
     * The status written to the event row, empty when the claim was lost and
     * so nothing was written.
     */
    public Optional<EventStatus> findStatus() {
        return Optional.ofNullable(status);
    }

    public Optional<Long> findTicketId() {
        return Optional.ofNullable(ticketId);
    }

    public Optional<String> findTicketNumber() {
        return Optional.ofNullable(ticketNumber);
    }

    /** Whether a new ticket was opened, as opposed to an existing one used. */
    public boolean isTicketRaised() {
        return ticketRaised;
    }

    public boolean isFailed() {
        return status == EventStatus.FAILED;
    }

    /** Whether this consumer is the one that dealt with the event. */
    public boolean isClaimed() {
        return status != null;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-12s %-15s %s",
                Displayable.orDash(eventReference),
                status == null ? "unclaimed" : status.getDisplayName(),
                message);
    }

    @Override
    public String toString() {
        return eventReference + ": " + message;
    }
}
