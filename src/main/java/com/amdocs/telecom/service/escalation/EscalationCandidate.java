package com.amdocs.telecom.service.escalation;

import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.enums.EscalationLevel;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.service.sla.SlaEvaluation;

import java.util.Comparator;

/**
 * A ticket the policy says has fallen behind, together with the rung it
 * should move to and the reason.
 *
 * <p>Nothing has happened to the ticket yet. This is the sweep's working
 * unit and the console's preview: a service desk can list what escalation
 * would do before letting it do anything.</p>
 *
 * <p>Section 9 ends with "critical tickets should be processed before
 * lower-priority tickets", which is what {@link #mostUrgentFirst()} is for.
 * It is supplied as a comparator rather than by implementing
 * {@code Comparable} because two candidates that tie on urgency are not the
 * same candidate, and a natural ordering that disagrees with equality is a
 * trap for anything that later puts these in a set.</p>
 */
public final class EscalationCandidate implements Displayable {

    private final TroubleTicket ticket;
    private final SlaEvaluation verdict;
    private final EscalationTrigger trigger;
    private final EscalationLevel fromLevel;
    private final EscalationLevel toLevel;

    EscalationCandidate(TroubleTicket ticket, SlaEvaluation verdict, EscalationTrigger trigger,
                        EscalationLevel fromLevel, EscalationLevel toLevel) {
        if (ticket == null || verdict == null || trigger == null
                || fromLevel == null || toLevel == null) {
            throw new IllegalArgumentException(
                    "A candidate needs a ticket, a verdict, a reason and both levels");
        }
        this.ticket = ticket;
        this.verdict = verdict;
        this.trigger = trigger;
        this.fromLevel = fromLevel;
        this.toLevel = toLevel;
    }

    /**
     * Most urgent first, which for a {@link java.util.PriorityQueue} means
     * a critical ticket is polled before anything below it, and among
     * equals the one that has been waiting longest goes first.
     */
    public static Comparator<EscalationCandidate> mostUrgentFirst() {
        return Comparator.comparing(EscalationCandidate::getTicket, TroubleTicket.byUrgency());
    }

    public TroubleTicket getTicket() {
        return ticket;
    }

    public SlaEvaluation getVerdict() {
        return verdict;
    }

    public EscalationTrigger getTrigger() {
        return trigger;
    }

    public EscalationLevel getFromLevel() {
        return fromLevel;
    }

    public EscalationLevel getToLevel() {
        return toLevel;
    }

    public String getTicketNumber() {
        return ticket.getTicketNumber();
    }

    public Priority getPriority() {
        return ticket.getPriority();
    }

    /** The sentence written into the escalation trail. */
    public String getReason() {
        return "Escalated automatically because " + trigger.getReason() + ".";
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-16s %-10s %-18s %-18s %s",
                Displayable.orDash(ticket.getTicketNumber()),
                ticket.getPriority() == null ? "-" : ticket.getPriority().getDisplayName(),
                fromLevel.getDisplayName(),
                toLevel.getDisplayName(),
                trigger.getReason());
    }

    /** Column headings matching {@link #toSummaryLine()}. */
    public static String summaryHeading() {
        return String.format("%-16s %-10s %-18s %-18s %s",
                "Ticket", "Priority", "Now with", "Moving to", "Because");
    }

    @Override
    public String toString() {
        return ticket.getTicketNumber() + " " + fromLevel + " -> " + toLevel;
    }
}
