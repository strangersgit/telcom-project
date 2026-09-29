package com.amdocs.telecom.service.impl;

import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.EscalationHistoryDAO;
import com.amdocs.telecom.dao.ReportDAO;
import com.amdocs.telecom.dao.TicketStatusHistoryDAO;
import com.amdocs.telecom.dao.TransactionTemplate;
import com.amdocs.telecom.dao.TroubleTicketDAO;
import com.amdocs.telecom.exception.BusinessException;
import com.amdocs.telecom.exception.ErrorCode;
import com.amdocs.telecom.exception.ResourceNotFoundException;
import com.amdocs.telecom.exception.ValidationException;
import com.amdocs.telecom.model.EscalationHistory;
import com.amdocs.telecom.model.TicketStatusHistory;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.enums.EscalationLevel;
import com.amdocs.telecom.model.enums.TicketStatus;
import com.amdocs.telecom.security.AccessControl;
import com.amdocs.telecom.security.Permission;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.EscalationService;
import com.amdocs.telecom.service.SlaService;
import com.amdocs.telecom.service.escalation.EscalationCandidate;
import com.amdocs.telecom.service.escalation.EscalationOutcome;
import com.amdocs.telecom.service.escalation.EscalationPolicy;
import com.amdocs.telecom.service.event.TicketEvent;
import com.amdocs.telecom.service.event.TicketEventPublisher;
import com.amdocs.telecom.service.sla.SlaEvaluation;
import com.amdocs.telecom.util.AppLogger;
import com.amdocs.telecom.validation.TicketLifecycle;
import com.amdocs.telecom.validation.TicketValidator;
import com.amdocs.telecom.validation.Validators;

import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Queue;

/**
 * Climbs the section 9 ladder, by hand and by SLA.
 *
 * <h3>One rung per move</h3>
 *
 * <p>Section 9 draws the ladder as four rungs with arrows between
 * neighbours, and {@code sp_escalate_ticket} moves exactly one rung per
 * call. This follows both. A breached ticket sitting with its engineer is
 * three rungs from the operations manager and reaches them over three
 * sweeps, leaving three lines in {@code escalation_history}. That is not a
 * limitation to work around: the team lead genuinely was told, and a trail
 * that jumped from engineer to operations manager would hide the fact that
 * two people were given a chance to fix it first.</p>
 *
 * <h3>Why a ticket nobody owns cannot be escalated</h3>
 *
 * <p>{@link TicketLifecycle} has no edge from OPEN to ESCALATED, and this
 * does not widen it. Escalating means handing a ticket to somebody more
 * senior than the person holding it, and an unescalated OPEN ticket is held
 * by nobody at all. The remedy for an unassigned ticket burning through its
 * window is to assign it, which is what the assignment sweep is for, so the
 * refusal here says so rather than inventing a state where a ticket has been
 * escalated past an engineer it never had.</p>
 *
 * <h3>Escalating something already escalated</h3>
 *
 * <p>Only the level moves. The status is already ESCALATED and a ticket may
 * never transition to the state it is in, so the second and third rungs are
 * level changes with the status left alone, the same shape as a
 * reassignment. The guarded update is what makes that safe: it is conditioned
 * on the rung that was read, so two escalations racing each other cannot both
 * move off the same level.</p>
 */
public final class EscalationServiceImpl implements EscalationService {

    /**
     * How many tickets one sweep will consider. A cap rather than the whole
     * table, because the sweep holds a transaction open while it runs.
     */
    public static final int MAX_SWEEP = 50;

    private static final int DEFAULT_SWEEP = 20;

    private final TroubleTicketDAO tickets;
    private final EscalationHistoryDAO escalations;
    private final TicketStatusHistoryDAO history;
    private final ReportDAO reports;
    private final SlaService sla;
    private final EscalationPolicy policy;
    private final TicketEventPublisher events;

    public EscalationServiceImpl() {
        this(DAOFactory.getInstance(), new SlaServiceImpl(), TicketEventPublisher.getInstance());
    }

    public EscalationServiceImpl(DAOFactory factory, SlaService sla, TicketEventPublisher events) {
        this.tickets = factory.getTroubleTicketDAO();
        this.escalations = factory.getEscalationHistoryDAO();
        this.history = factory.getTicketStatusHistoryDAO();
        this.reports = factory.getReportDAO();
        if (sla == null) {
            throw new IllegalArgumentException("An SLA service is required");
        }
        if (events == null) {
            throw new IllegalArgumentException("A ticket event publisher is required");
        }
        this.sla = sla;
        this.events = events;
        this.policy = new EscalationPolicy();
    }

    /* ---------- Looking before leaping ---------- */

    @Override
    public Optional<EscalationCandidate> assess(UserSession actor, String ticketNumber) {
        TroubleTicket ticket = authorise(actor, ticketNumber);
        return candidateFor(ticket);
    }

    @Override
    public String explain(UserSession actor, String ticketNumber) {
        TroubleTicket ticket = authorise(actor, ticketNumber);
        SlaEvaluation verdict = sla.evaluate(ticket);
        String standing = policy.describe(ticket.getEscalationLevel(), verdict);
        Optional<String> refusal = refusalFor(ticket);
        if (refusal.isPresent()) {
            return standing + " " + refusal.get();
        }
        if (isTopOfLadder(ticket)) {
            return standing + " There is no higher level to reach.";
        }
        return standing;
    }

    @Override
    public List<EscalationCandidate> candidates(UserSession actor, int limit) {
        AccessControl.require(actor, Permission.ESCALATE_TICKET);
        return drain(queueOfCandidates(), boundedSweep(limit));
    }

    /**
     * Every open ticket the policy says has fallen behind, in a queue that
     * hands back the most urgent first.
     *
     * <p>Section 9's "critical tickets should be processed before
     * lower-priority tickets" is the whole reason this is a
     * {@link PriorityQueue} rather than a list read in table order. The
     * ordering lives in {@link EscalationCandidate#mostUrgentFirst()}, which
     * defers to the ticket's own urgency comparator, so the queue and every
     * other urgency ordered view of the same tickets agree.</p>
     */
    private Queue<EscalationCandidate> queueOfCandidates() {
        List<TroubleTicket> open = tickets.findOpen();
        Queue<EscalationCandidate> queue =
                new PriorityQueue<EscalationCandidate>(Math.max(open.size(), 1),
                        EscalationCandidate.mostUrgentFirst());
        for (TroubleTicket ticket : open) {
            Optional<EscalationCandidate> candidate = candidateFor(ticket);
            if (candidate.isPresent()) {
                queue.offer(candidate.get());
            }
        }
        return queue;
    }

    /**
     * Polls the queue rather than iterating it, because a
     * {@link PriorityQueue}'s iterator gives no ordering guarantee at all.
     * Only {@code poll} sees the heap in order.
     */
    private static List<EscalationCandidate> drain(Queue<EscalationCandidate> queue, int limit) {
        List<EscalationCandidate> ordered = new ArrayList<EscalationCandidate>();
        while (!queue.isEmpty() && ordered.size() < limit) {
            ordered.add(queue.poll());
        }
        return ordered;
    }

    /**
     * Asks the policy what should become of one ticket, judging it as at
     * this moment.
     *
     * <p>A ticket escalation would refuse is not a candidate, however far
     * behind it is. The two questions have to be answered together in one
     * place: if the preview said yes where the operation would say no, the
     * console would offer a ticket that cannot be escalated and the sweep
     * would fill its report with refusals it could have foreseen.</p>
     */
    private Optional<EscalationCandidate> candidateFor(TroubleTicket ticket) {
        if (refusalFor(ticket).isPresent()) {
            return Optional.empty();
        }
        return policy.assess(ticket, sla.evaluate(ticket));
    }

    /* ---------- Climbing ---------- */

    @Override
    public EscalationOutcome escalate(UserSession actor, String ticketNumber, String reason) {
        TicketValidator.validateReason("The ticket could not be escalated", "Reason", reason);
        TroubleTicket ticket = authorise(actor, ticketNumber);

        Optional<String> refusal = refusalFor(ticket);
        if (refusal.isPresent()) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, refusal.get());
        }
        EscalationLevel from = levelOf(ticket);
        EscalationLevel to = from.next().orElseThrow(() ->
                new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                        "Ticket " + ticket.getTicketNumber() + " is already with the "
                                + from.getDisplayName() + ", which is the top of the ladder. "
                                + "There is nobody further to escalate it to."));

        return TransactionTemplate.execute(context ->
                climb(actor, ticket, from, to, reason.trim(), false));
    }

    @Override
    public List<EscalationOutcome> sweep(UserSession actor, int limit) {
        AccessControl.require(actor, Permission.ESCALATE_TICKET);
        List<EscalationCandidate> queue = drain(queueOfCandidates(), boundedSweep(limit));
        if (queue.isEmpty()) {
            return new ArrayList<EscalationOutcome>();
        }

        return TransactionTemplate.execute(context -> {
            List<EscalationOutcome> outcomes = new ArrayList<EscalationOutcome>(queue.size());
            for (EscalationCandidate candidate : queue) {
                TroubleTicket ticket = candidate.getTicket();
                Savepoint marker = context.savepoint("escalate_" + ticket.getId());
                try {
                    outcomes.add(climb(actor, ticket, candidate.getFromLevel(),
                            candidate.getToLevel(), candidate.getReason(), true));
                    context.release(marker);
                } catch (BusinessException refused) {
                    // One ticket that cannot be escalated must not abandon
                    // the queue behind it. Only a business refusal is
                    // survivable: a database failure arrives as
                    // DataAccessException and is left to propagate, because
                    // past that point nothing in the transaction is
                    // trustworthy.
                    context.rollbackTo(marker);
                    outcomes.add(EscalationOutcome.skipped(ticket.getTicketNumber(),
                            refused.getMessage()));
                }
            }
            AppLogger.info(EscalationServiceImpl.class, "Escalation sweep by '"
                    + actor.getUsername() + "' raised " + countEscalated(outcomes)
                    + " of " + outcomes.size() + " ticket(s) considered");
            return outcomes;
        });
    }

    /**
     * Moves one ticket one rung, with the trail it owes.
     *
     * <p>Runs no transaction of its own so that a sweep can put many of
     * these inside one, the same arrangement the assignment engine uses.</p>
     */
    private EscalationOutcome climb(UserSession actor, TroubleTicket ticket,
                                    EscalationLevel from, EscalationLevel to, String reason,
                                    boolean automatic) {
        TicketStatus status = ticket.getStatus();

        // A ticket already escalated stays escalated: the level rises but
        // the state does not move, and asking the graph for ESCALATED to
        // ESCALATED would rightly be refused.
        if (status != TicketStatus.ESCALATED) {
            TicketLifecycle.requireTransition(ticket.getTicketNumber(), status,
                    TicketStatus.ESCALATED);
        }

        // Guarded on both the rung and the status that were read, so an
        // escalation prepared against a stale row is refused rather than
        // overwriting whatever happened in between.
        if (!tickets.escalate(ticket.getId(), from, to, status)) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Ticket " + ticket.getTicketNumber() + " was " + status.getDisplayName()
                            + " with the " + from.getDisplayName() + " when this escalation was "
                            + "prepared, but has since moved. Look at it again before retrying.");
        }

        escalations.insert(new EscalationHistory(ticket.getId(), from, to,
                Validators.shorten(reason, TicketValidator.REMARKS_MAX),
                actor.getUsername(), automatic));

        history.insert(new TicketStatusHistory(ticket.getId(), status, TicketStatus.ESCALATED,
                actor.getUsername(), Validators.shorten("Escalated from "
                        + from.getDisplayName() + " to " + to.getDisplayName() + ": " + reason,
                TicketValidator.REMARKS_MAX)));

        // The notification and the audit record are consequences of the
        // announcement rather than work this method does, and both run
        // inside whatever transaction the caller opened.
        events.publish(TicketEvent.escalated(tickets.getById(ticket.getId()), actor,
                from, to, reason));

        AppLogger.info(EscalationServiceImpl.class, "Ticket " + ticket.getTicketNumber()
                + " escalated from " + from.getDisplayName() + " to " + to.getDisplayName()
                + " by '" + actor.getUsername() + "'" + (automatic ? " (automatic)" : ""));
        return EscalationOutcome.moved(ticket.getTicketNumber(), from, to, automatic);
    }

    /* ---------- The trail ---------- */

    @Override
    public List<EscalationHistory> history(UserSession actor, String ticketNumber) {
        TroubleTicket ticket = authorise(actor, ticketNumber);
        return escalations.findByTicketId(ticket.getId());
    }

    @Override
    public List<EscalationHistory> automatic(UserSession actor) {
        AccessControl.require(actor, Permission.ESCALATE_TICKET);
        return escalations.findAutoEscalated();
    }

    @Override
    public ReportDAO.ProcedureOutcome escalateViaProcedure(UserSession actor, String ticketNumber,
                                                           String reason, boolean automatic) {
        TicketValidator.validateReason("The ticket could not be escalated", "Reason", reason);
        TroubleTicket ticket = authorise(actor, ticketNumber);
        return reports.escalateViaProcedure(ticket.getId(), reason.trim(), actor.getUsername(),
                automatic);
    }

    /* ---------- Shared ---------- */

    /**
     * Loads the ticket and checks the caller may escalate at all.
     *
     * <p>{@link Permission#ESCALATE_TICKET} is held by the service desk, the
     * engineers and the network manager. There is no per-ticket ownership
     * test: an engineer escalating a ticket is asking for help, and refusing
     * because the ticket is not theirs would be refusing to let one engineer
     * flag a problem another has stalled on.</p>
     */
    private TroubleTicket authorise(UserSession actor, String ticketNumber) {
        AccessControl.require(actor, Permission.ESCALATE_TICKET);
        if (Validators.isBlank(ticketNumber)) {
            throw new ValidationException("A ticket number is required");
        }
        String trimmed = ticketNumber.trim();
        return tickets.findByTicketNumber(trimmed)
                .orElseThrow(() -> new ResourceNotFoundException("trouble_tickets", trimmed));
    }

    /**
     * Why this ticket cannot be escalated at all, empty when it can.
     *
     * <p>Separate from the policy because these are facts about the ticket
     * rather than about its SLA standing, and they refuse a person's request
     * just as firmly as they exclude a ticket from the sweep.</p>
     */
    private Optional<String> refusalFor(TroubleTicket ticket) {
        if (ticket.getStatus() == null || ticket.getStatus().isFinished()) {
            return Optional.of("Ticket " + ticket.getTicketNumber() + " is "
                    + (ticket.getStatus() == null ? "in no known state"
                    : ticket.getStatus().getDisplayName())
                    + ". A ticket that has finished cannot be escalated.");
        }
        if (ticket.getAssignedEngineerId() == null) {
            return Optional.of("Ticket " + ticket.getTicketNumber() + " has nobody working on "
                    + "it, so there is no one to escalate past. Assign an engineer first.");
        }
        if (isTopOfLadder(ticket)) {
            return Optional.of("Ticket " + ticket.getTicketNumber() + " is already with the "
                    + levelOf(ticket).getDisplayName() + ", the top of the ladder.");
        }
        return Optional.empty();
    }

    private boolean isTopOfLadder(TroubleTicket ticket) {
        return levelOf(ticket).isTopLevel();
    }

    /**
     * The rung the ticket sits on, treating a row with none as being at the
     * bottom, because every ticket starts with its engineer.
     */
    private static EscalationLevel levelOf(TroubleTicket ticket) {
        return ticket.getEscalationLevel() == null
                ? EscalationLevel.ENGINEER
                : ticket.getEscalationLevel();
    }

    private static long countEscalated(List<EscalationOutcome> outcomes) {
        return outcomes.stream().filter(EscalationOutcome::isEscalated).count();
    }

    private static int boundedSweep(int requested) {
        if (requested <= 0) {
            return DEFAULT_SWEEP;
        }
        return Math.min(requested, MAX_SWEEP);
    }
}
