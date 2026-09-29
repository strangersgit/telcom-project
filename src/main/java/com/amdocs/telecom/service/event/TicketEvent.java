package com.amdocs.telecom.service.event;

import com.amdocs.telecom.model.Feedback;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.enums.EscalationLevel;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.model.enums.TicketStatus;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.validation.Validators;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Something that happened to a ticket, handed to every interested
 * {@link TicketEventListener}.
 *
 * <p>The class is immutable and is only built through the static factory
 * methods below. That is deliberate: a listener receives the same event
 * object as every other listener, and one of them quietly amending a field
 * would change what the next one sees.</p>
 *
 * <p>Three fields carry the detail, and they are strings rather than a
 * field per event kind. A status change describes itself as OPEN to
 * ASSIGNED, an escalation as ENGINEER to SENIOR_ENGINEER, a priority change
 * as P3 to P1: all of them are one value becoming another, and both the
 * audit trail and the notification wording want them as text. Keeping three
 * general fields rather than a dozen specific ones means a new event kind
 * needs a factory method and nothing else.</p>
 *
 * <ul>
 *   <li>{@code fromValue} — what it was, where there was a previous value
 *   <li>{@code toValue} — what it became
 *   <li>{@code note} — the reason, remark or measurement that goes with it
 * </ul>
 *
 * <p>The ticket held here is the ticket as it stood immediately after the
 * change, so a listener reading {@code getTicket().getStatus()} sees the new
 * status rather than the old one.</p>
 */
public final class TicketEvent {

    /**
     * The actor recorded for events the background monitor raises. It
     * matches what {@code AuditLog.isSystemGenerated()} looks for, so those
     * rows are distinguishable from anything a person did.
     */
    public static final String SYSTEM_ACTOR = "SYSTEM";

    private final TicketEventType type;
    private final TroubleTicket ticket;
    private final String actor;
    private final LocalDateTime occurredAt;
    private final String fromValue;
    private final String toValue;
    private final String note;

    private TicketEvent(TicketEventType type, TroubleTicket ticket, String actor,
                        String fromValue, String toValue, String note) {
        if (type == null) {
            throw new IllegalArgumentException("A ticket event needs a type");
        }
        if (ticket == null) {
            throw new IllegalArgumentException("A ticket event needs a ticket");
        }
        if (ticket.getId() == null || Validators.isBlank(ticket.getTicketNumber())) {
            throw new IllegalArgumentException(
                    "A ticket event needs a stored ticket: both its id and its number"
                            + " are used by the listeners");
        }
        if (Validators.isBlank(actor)) {
            throw new IllegalArgumentException("A ticket event needs an actor");
        }
        this.type = type;
        this.ticket = ticket;
        this.actor = actor.trim();
        this.occurredAt = LocalDateTime.now().withNano(0);
        this.fromValue = Validators.trimToNull(fromValue);
        this.toValue = Validators.trimToNull(toValue);
        this.note = Validators.trimToNull(note);
    }

    /* ---------- Raising and assignment ---------- */

    /**
     * @param detail what the ticket is about, in the wording the service
     *               composes for the trail
     */
    public static TicketEvent raised(TroubleTicket ticket, UserSession actor, String detail) {
        return new TicketEvent(TicketEventType.RAISED, ticket, usernameOf(actor),
                null, TicketStatus.OPEN.name(), detail);
    }

    /**
     * A ticket the event processor opened on its own, with no person behind
     * it.
     *
     * <p>Section 11 has a background thread create tickets from network
     * alarms, and section 12 asks for a notification on ticket creation, so
     * these have to be announced like any other. There is no session to
     * attribute them to, which is why this records {@link #SYSTEM_ACTOR}
     * rather than taking one.</p>
     *
     * @param detail what the alarm was, so the customer's notification says
     *               why a ticket appeared that they did not raise
     */
    public static TicketEvent autoRaised(TroubleTicket ticket, String detail) {
        if (Validators.isBlank(detail)) {
            throw new IllegalArgumentException(
                    "An automatic ticket needs the alarm that caused it recorded");
        }
        return new TicketEvent(TicketEventType.RAISED, ticket, SYSTEM_ACTOR,
                null, TicketStatus.OPEN.name(), detail);
    }

    /**
     * @param engineerCode the engineer's staff code, which is what the
     *                     customer sees in the notification rather than a
     *                     database key
     */
    public static TicketEvent assigned(TroubleTicket ticket, UserSession actor,
                                       String previousEngineerCode, String engineerCode,
                                       String note) {
        if (Validators.isBlank(engineerCode)) {
            throw new IllegalArgumentException(
                    "An assignment event needs the engineer's code for the notification");
        }
        return new TicketEvent(TicketEventType.ASSIGNED, ticket, usernameOf(actor),
                previousEngineerCode, engineerCode, note);
    }

    /* ---------- Working the ticket ---------- */

    public static TicketEvent statusChanged(TroubleTicket ticket, UserSession actor,
                                            TicketStatus from, TicketStatus to, String remarks) {
        return new TicketEvent(TicketEventType.STATUS_CHANGED, ticket, usernameOf(actor),
                nameOf(from), nameOf(to), remarks);
    }

    public static TicketEvent priorityChanged(TroubleTicket ticket, UserSession actor,
                                              Priority from, Priority to, String reason) {
        return new TicketEvent(TicketEventType.PRIORITY_CHANGED, ticket, usernameOf(actor),
                nameOf(from), nameOf(to), reason);
    }

    public static TicketEvent diagnosisRecorded(TroubleTicket ticket, UserSession actor,
                                                String previousRootCause, String rootCause) {
        return new TicketEvent(TicketEventType.DIAGNOSIS_RECORDED, ticket, usernameOf(actor),
                previousRootCause, rootCause, null);
    }

    public static TicketEvent firstResponse(TroubleTicket ticket, UserSession actor,
                                            LocalDateTime respondedAt) {
        return new TicketEvent(TicketEventType.FIRST_RESPONSE, ticket, usernameOf(actor),
                null, respondedAt == null ? null : respondedAt.toString(), null);
    }

    /* ---------- Settling the ticket ---------- */

    public static TicketEvent resolved(TroubleTicket ticket, UserSession actor,
                                       TicketStatus from, String note) {
        return new TicketEvent(TicketEventType.RESOLVED, ticket, usernameOf(actor),
                nameOf(from), TicketStatus.RESOLVED.name(), note);
    }

    public static TicketEvent closed(TroubleTicket ticket, UserSession actor,
                                     TicketStatus from, String remarks) {
        return new TicketEvent(TicketEventType.CLOSED, ticket, usernameOf(actor),
                nameOf(from), TicketStatus.CLOSED.name(), remarks);
    }

    public static TicketEvent cancelled(TroubleTicket ticket, UserSession actor,
                                        TicketStatus from, String reason) {
        return new TicketEvent(TicketEventType.CANCELLED, ticket, usernameOf(actor),
                nameOf(from), TicketStatus.CANCELLED.name(), reason);
    }

    public static TicketEvent reopened(TroubleTicket ticket, UserSession actor,
                                       TicketStatus from, String reason) {
        return new TicketEvent(TicketEventType.REOPENED, ticket, usernameOf(actor),
                nameOf(from), TicketStatus.IN_PROGRESS.name(), reason);
    }

    public static TicketEvent feedbackSubmitted(TroubleTicket ticket, UserSession actor,
                                                int rating, String comments) {
        return new TicketEvent(TicketEventType.FEEDBACK_SUBMITTED, ticket, usernameOf(actor),
                null, rating + " of " + Feedback.MAX_RATING, comments);
    }

    /* ---------- Oversight ---------- */

    public static TicketEvent escalated(TroubleTicket ticket, UserSession actor,
                                        EscalationLevel from, EscalationLevel to, String reason) {
        if (from == null || to == null) {
            throw new IllegalArgumentException(
                    "An escalation event needs both levels for the notification");
        }
        return new TicketEvent(TicketEventType.ESCALATED, ticket, usernameOf(actor),
                from.getDisplayName(), to.getDisplayName(), reason);
    }

    /**
     * Raised by the background monitor rather than by anybody, which is why
     * this and {@link #slaBreached} are the two factories that do not take a
     * session and record {@link #SYSTEM_ACTOR} instead.
     *
     * @param timeRemaining the wording shown in the warning, already
     *                      formatted, because the SLA engine owns how a
     *                      duration reads
     */
    public static TicketEvent slaAtRisk(TroubleTicket ticket, String timeRemaining) {
        if (Validators.isBlank(timeRemaining)) {
            throw new IllegalArgumentException(
                    "An SLA warning needs the time remaining for the notification");
        }
        return new TicketEvent(TicketEventType.SLA_AT_RISK, ticket, SYSTEM_ACTOR,
                null, null, timeRemaining);
    }

    public static TicketEvent slaBreached(TroubleTicket ticket, String detail) {
        return new TicketEvent(TicketEventType.SLA_BREACHED, ticket, SYSTEM_ACTOR,
                null, null, detail);
    }

    /* ---------- Reading ---------- */

    public TicketEventType getType() {
        return type;
    }

    public TroubleTicket getTicket() {
        return ticket;
    }

    public Long getTicketId() {
        return ticket.getId();
    }

    public String getTicketNumber() {
        return ticket.getTicketNumber();
    }

    /**
     * The username that caused this, or {@link #SYSTEM_ACTOR}.
     */
    public String getActor() {
        return actor;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public Optional<String> findFromValue() {
        return Optional.ofNullable(fromValue);
    }

    public Optional<String> findToValue() {
        return Optional.ofNullable(toValue);
    }

    public Optional<String> findNote() {
        return Optional.ofNullable(note);
    }

    private static String usernameOf(UserSession actor) {
        if (actor == null) {
            throw new IllegalArgumentException("A ticket event needs the session that caused it");
        }
        return actor.getUsername();
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder()
                .append(type.name()).append(' ').append(getTicketNumber())
                .append(" by ").append(actor);
        if (fromValue != null || toValue != null) {
            text.append(" [").append(fromValue == null ? "-" : fromValue)
                    .append(" -> ").append(toValue == null ? "-" : toValue).append(']');
        }
        return text.toString();
    }
}
