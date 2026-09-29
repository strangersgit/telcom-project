package com.amdocs.telecom.service.sla;

import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.SLAConfiguration;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.model.enums.SLAStatus;
import com.amdocs.telecom.model.enums.TicketStatus;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * What the SLA engine makes of one ticket at one moment.
 *
 * <p>Immutable, and carries the moment it was taken, because an SLA standing
 * is only true as of a particular time. Keeping the answer in a value object
 * rather than mutating the ticket means a dashboard can show a live figure
 * without writing to the database, and the background monitor can compare
 * what it computed against what is stored.</p>
 *
 * <p>The decision mirrors {@code fn_sla_status} in {@code 02_functions.sql}
 * clause for clause. That duplication is deliberate: the case study requires
 * the SLA arithmetic in the database so views and ad hoc queries agree with
 * it, and requires it in Java so the engine does not need a round trip per
 * ticket. The verification harness asserts the two agree.</p>
 */
public final class SlaEvaluation implements Displayable {

    private final Long ticketId;
    private final String ticketNumber;
    private final Priority priority;
    private final TicketStatus ticketStatus;
    private final SLAStatus storedStatus;
    private final SLAStatus liveStatus;
    private final LocalDateTime raisedAt;
    private final LocalDateTime responseDeadline;
    private final LocalDateTime resolutionDeadline;
    private final LocalDateTime firstResponseAt;
    private final LocalDateTime resolvedAt;
    private final Long minutesRemaining;
    private final double consumedFraction;
    private final boolean responseBreached;
    private final String clockName;
    private final LocalDateTime evaluatedAt;

    private SlaEvaluation(TroubleTicket ticket, SLAStatus liveStatus, Long minutesRemaining,
                          double consumedFraction, boolean responseBreached,
                          String clockName, LocalDateTime evaluatedAt) {
        this.ticketId = ticket.getId();
        this.ticketNumber = ticket.getTicketNumber();
        this.priority = ticket.getPriority();
        this.ticketStatus = ticket.getStatus();
        this.storedStatus = ticket.getSlaStatus();
        this.raisedAt = ticket.getCreatedDate();
        this.responseDeadline = ticket.getSlaResponseDeadline();
        this.resolutionDeadline = ticket.getSlaDeadline();
        this.firstResponseAt = ticket.getFirstResponseDate();
        this.resolvedAt = ticket.getResolutionDate();
        this.liveStatus = liveStatus;
        this.minutesRemaining = minutesRemaining;
        this.consumedFraction = consumedFraction;
        this.responseBreached = responseBreached;
        this.clockName = clockName;
        this.evaluatedAt = evaluatedAt;
    }

    /**
     * Judges a ticket against its band's windows.
     *
     * @param ticket        the ticket, which must carry a priority and a raise date
     * @param configuration the windows for that ticket's band
     * @param clock         the counting rule in force for that band
     * @param at            the moment to judge it at, normally now
     */
    public static SlaEvaluation of(TroubleTicket ticket, SLAConfiguration configuration,
                                   SlaClock clock, LocalDateTime at) {
        if (ticket == null || configuration == null || clock == null || at == null) {
            throw new IllegalArgumentException(
                    "A ticket, its SLA configuration, a clock and a moment are all required");
        }

        LocalDateTime deadline = ticket.getSlaDeadline();
        double consumed = consumedFraction(ticket, clock, at);

        return new SlaEvaluation(ticket,
                decideStatus(ticket, configuration, consumed, at),
                minutesRemaining(deadline, at),
                consumed,
                decideResponseBreach(ticket, at),
                clock.getName(),
                at);
    }

    /**
     * The same sequence of tests as {@code fn_sla_status}: a finished ticket
     * is settled against the deadline it had, a cancelled one never breaches,
     * and an open one is judged against the clock.
     */
    private static SLAStatus decideStatus(TroubleTicket ticket, SLAConfiguration configuration,
                                          double consumed, LocalDateTime at) {
        LocalDateTime deadline = ticket.getSlaDeadline();
        if (deadline == null) {
            return SLAStatus.WITHIN_SLA;
        }

        TicketStatus status = ticket.getStatus();
        if (status == TicketStatus.RESOLVED || status == TicketStatus.CLOSED) {
            LocalDateTime resolvedAt = ticket.getResolutionDate();
            return resolvedAt != null && resolvedAt.isAfter(deadline)
                    ? SLAStatus.BREACHED
                    : SLAStatus.WITHIN_SLA;
        }
        if (status == TicketStatus.CANCELLED) {
            return SLAStatus.WITHIN_SLA;
        }

        if (at.isAfter(deadline)) {
            return SLAStatus.BREACHED;
        }
        double threshold = configuration.getAtRiskThresholdPercent() / 100.0d;
        return consumed >= threshold ? SLAStatus.AT_RISK : SLAStatus.WITHIN_SLA;
    }

    private static double consumedFraction(TroubleTicket ticket, SlaClock clock,
                                           LocalDateTime at) {
        LocalDateTime raisedAt = ticket.getCreatedDate();
        LocalDateTime deadline = ticket.getSlaDeadline();
        if (raisedAt == null || deadline == null) {
            return 0.0d;
        }
        // A finished ticket stops consuming its window when it was resolved,
        // not when someone happens to look at it.
        LocalDateTime upTo = ticket.getResolutionDate() == null ? at : ticket.getResolutionDate();
        return clock.consumedFraction(raisedAt, deadline, upTo);
    }

    /**
     * Wall clock minutes left, matching {@code fn_minutes_remaining} so the
     * console and the views quote the same number. The clock aware view of
     * the same thing is {@link #getConsumedPercent()}.
     */
    private static Long minutesRemaining(LocalDateTime deadline, LocalDateTime at) {
        return deadline == null ? null
                : Long.valueOf(ChronoUnit.MINUTES.between(at, deadline));
    }

    /**
     * A response breach is a first response that arrived late, or a window
     * that has run out with no response at all. A cancelled ticket is exempt,
     * for the same reason it cannot breach its resolution window.
     */
    private static boolean decideResponseBreach(TroubleTicket ticket, LocalDateTime at) {
        LocalDateTime deadline = ticket.getSlaResponseDeadline();
        if (deadline == null) {
            return false;
        }
        if (ticket.getFirstResponseDate() != null) {
            return ticket.getFirstResponseDate().isAfter(deadline);
        }
        if (ticket.getStatus() == TicketStatus.CANCELLED) {
            return false;
        }
        return at.isAfter(deadline);
    }

    /* ---------- Accessors ---------- */

    public Long getTicketId() {
        return ticketId;
    }

    public String getTicketNumber() {
        return ticketNumber;
    }

    public Priority getPriority() {
        return priority;
    }

    public TicketStatus getTicketStatus() {
        return ticketStatus;
    }

    /** What the {@code sla_status} column said when the ticket was read. */
    public SLAStatus getStoredStatus() {
        return storedStatus;
    }

    /** What the engine computes now. */
    public SLAStatus getLiveStatus() {
        return liveStatus;
    }

    public LocalDateTime getRaisedAt() {
        return raisedAt;
    }

    public LocalDateTime getResponseDeadline() {
        return responseDeadline;
    }

    public LocalDateTime getResolutionDeadline() {
        return resolutionDeadline;
    }

    public LocalDateTime getFirstResponseAt() {
        return firstResponseAt;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    /**
     * Minutes to the deadline, negative once it has passed, empty when the
     * ticket has no deadline at all.
     */
    public Optional<Long> findMinutesRemaining() {
        return Optional.ofNullable(minutesRemaining);
    }

    public double getConsumedFraction() {
        return consumedFraction;
    }

    public double getConsumedPercent() {
        return consumedFraction * 100.0d;
    }

    public boolean isResponseBreached() {
        return responseBreached;
    }

    public String getClockName() {
        return clockName;
    }

    public LocalDateTime getEvaluatedAt() {
        return evaluatedAt;
    }

    /* ---------- Derived ---------- */

    public boolean isBreached() {
        return liveStatus == SLAStatus.BREACHED;
    }

    public boolean isAtRisk() {
        return liveStatus == SLAStatus.AT_RISK;
    }

    /** Still counts as work in progress. */
    public boolean isOpen() {
        return ticketStatus != null && ticketStatus.isActive();
    }

    /**
     * Whether the stored column disagrees with the live computation, which
     * is what the monitor writes back.
     */
    public boolean isStale() {
        return storedStatus != liveStatus;
    }

    /**
     * Whether the SLA monitor should raise this ticket with somebody.
     *
     * <p>A breach on an open ticket always warrants attention. Merely being
     * at risk does not while the operator is legitimately waiting on the
     * customer, which is the distinction
     * {@link TicketStatus#isSlaClockRunning()} draws.</p>
     */
    public boolean needsAttention() {
        if (!isOpen()) {
            return false;
        }
        if (isBreached()) {
            return true;
        }
        return isAtRisk() && ticketStatus.isSlaClockRunning();
    }

    /* ---------- Rendering ---------- */

    @Override
    public String toSummaryLine() {
        return String.format("%-16s %-10s %-16s %-11s %-17s %8s %7.0f%%",
                Displayable.orDash(ticketNumber),
                priority == null ? "-" : priority.getDisplayName(),
                ticketStatus == null ? "-" : ticketStatus.getDisplayName(),
                liveStatus == null ? "-" : liveStatus.getDisplayName(),
                Displayable.formatDateTime(resolutionDeadline),
                minutesRemaining == null ? "-" : String.valueOf(minutesRemaining),
                getConsumedPercent());
    }

    /**
     * Column headings matching {@link #toSummaryLine()}.
     */
    public static String summaryHeading() {
        return String.format("%-16s %-10s %-16s %-11s %-17s %8s %8s",
                "Ticket", "Priority", "Status", "SLA", "Deadline", "Mins", "Used");
    }

    @Override
    public String toDetailBlock() {
        StringBuilder builder = new StringBuilder();
        builder.append(Displayable.labelled("Ticket", ticketNumber)).append(System.lineSeparator());
        builder.append(Displayable.labelled("Priority",
                priority == null ? null : priority.getDisplayName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Clock", clockName)).append(System.lineSeparator());
        builder.append(Displayable.labelled("Raised", Displayable.formatDateTime(raisedAt)))
                .append(System.lineSeparator());
        builder.append(Displayable.labelled("Response due",
                Displayable.formatDateTime(responseDeadline))).append(System.lineSeparator());
        builder.append(Displayable.labelled("Responded",
                Displayable.formatDateTime(firstResponseAt))).append(System.lineSeparator());
        builder.append(Displayable.labelled("Response SLA",
                responseBreached ? "Breached" : "Met")).append(System.lineSeparator());
        builder.append(Displayable.labelled("Resolution due",
                Displayable.formatDateTime(resolutionDeadline))).append(System.lineSeparator());
        builder.append(Displayable.labelled("Resolved",
                Displayable.formatDateTime(resolvedAt))).append(System.lineSeparator());
        builder.append(Displayable.labelled("Window used",
                String.format("%.1f%%", getConsumedPercent()))).append(System.lineSeparator());
        builder.append(Displayable.labelled("SLA status",
                liveStatus == null ? null : liveStatus.getDisplayName()));
        if (isStale()) {
            builder.append(System.lineSeparator());
            builder.append(Displayable.labelled("Stored as",
                    storedStatus == null ? null : storedStatus.getDisplayName()));
        }
        return builder.toString();
    }

    @Override
    public String toString() {
        return ticketNumber + " " + liveStatus + " at " + evaluatedAt;
    }
}
