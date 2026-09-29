package com.amdocs.telecom.service.impl;

import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.FeedbackDAO;
import com.amdocs.telecom.dao.ReportDAO;
import com.amdocs.telecom.dao.TelecomServiceDAO;
import com.amdocs.telecom.dao.TicketStatusHistoryDAO;
import com.amdocs.telecom.dao.TransactionTemplate;
import com.amdocs.telecom.dao.TroubleTicketDAO;
import com.amdocs.telecom.dto.TicketDetailDTO;
import com.amdocs.telecom.dto.TicketRequest;
import com.amdocs.telecom.exception.AuthorizationException;
import com.amdocs.telecom.exception.BusinessException;
import com.amdocs.telecom.exception.DuplicateResourceException;
import com.amdocs.telecom.exception.ErrorCode;
import com.amdocs.telecom.exception.ResourceNotFoundException;
import com.amdocs.telecom.exception.ValidationException;
import com.amdocs.telecom.model.Feedback;
import com.amdocs.telecom.model.TelecomService;
import com.amdocs.telecom.model.TicketStatusHistory;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.model.enums.ResolutionCode;
import com.amdocs.telecom.model.enums.Role;
import com.amdocs.telecom.model.enums.SLAStatus;
import com.amdocs.telecom.model.enums.Severity;
import com.amdocs.telecom.model.enums.TicketStatus;
import com.amdocs.telecom.security.AccessControl;
import com.amdocs.telecom.security.Permission;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.SlaService;
import com.amdocs.telecom.service.TicketService;
import com.amdocs.telecom.service.event.TicketEvent;
import com.amdocs.telecom.service.event.TicketEventPublisher;
import com.amdocs.telecom.service.sla.SlaEvaluation;
import com.amdocs.telecom.util.AppLogger;
import com.amdocs.telecom.validation.TicketLifecycle;
import com.amdocs.telecom.validation.TicketValidator;
import com.amdocs.telecom.validation.Validators;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The ticket lifecycle.
 *
 * <p>Three habits run through every mutation here, and they are worth
 * stating once rather than repeating in each method.</p>
 *
 * <p>First, read then compare-and-set. Every method reads the ticket,
 * decides whether what is being asked is legal, and then writes with the
 * status it read as a guard. Between the read and the write somebody else
 * may have moved the ticket; the guard turns that into a refusal the caller
 * can act on instead of a ticket that skipped a state. That is why the DAO
 * takes an expected status and why a false return becomes an exception here
 * rather than being ignored.</p>
 *
 * <p>Second, one transaction per change, covering the ticket row, the
 * status history entry and everything the listeners write. Section 5
 * promises a status trail and section 17 promises an audit log; a ticket
 * that moved without either recording it would make both promises false.
 * Publishing inside the transaction is what keeps that true, since a
 * listener that cannot write its row takes the whole change down with
 * it.</p>
 *
 * <p>Third, the columns that change together are written together. There is
 * no general purpose status setter that could leave a resolved ticket
 * without a resolution.</p>
 *
 * <p>The audit entries are no longer written here. Each method announces
 * what happened through {@link TicketEventPublisher} and the listeners in
 * {@link com.amdocs.telecom.service.event} decide what that is worth:
 * an audit row always, a notification when section 12 asks for one. The
 * status history stays, because it is a foreign keyed part of the ticket
 * rather than a record about the system; the package comment there sets out
 * the distinction.</p>
 */
public final class TicketServiceImpl implements TicketService {

    /**
     * States {@link #changeStatus} will set. The rest are reachable only
     * through the method that also writes the columns they require.
     */
    private static final Set<TicketStatus> DIRECTLY_SETTABLE =
            Collections.unmodifiableSet(EnumSet.of(TicketStatus.IN_PROGRESS,
                    TicketStatus.PENDING_CUSTOMER));

    /**
     * How many times a ticket number collision is worth retrying. A
     * collision means another writer took the number; re-reading the table
     * and asking again resolves it. Three attempts failing would mean
     * something other than a race.
     */
    private static final int NUMBER_ATTEMPTS = 3;

    private final TroubleTicketDAO tickets;
    private final TicketStatusHistoryDAO history;
    private final TelecomServiceDAO services;
    private final FeedbackDAO feedback;
    private final ReportDAO reports;
    private final SlaService sla;
    private final TicketNumberGenerator numbers;
    private final TicketEventPublisher events;

    public TicketServiceImpl() {
        this(DAOFactory.getInstance(), new SlaServiceImpl(), TicketEventPublisher.getInstance());
    }

    public TicketServiceImpl(DAOFactory factory, SlaService sla, TicketEventPublisher events) {
        this.tickets = factory.getTroubleTicketDAO();
        this.history = factory.getTicketStatusHistoryDAO();
        this.services = factory.getTelecomServiceDAO();
        this.feedback = factory.getFeedbackDAO();
        this.reports = factory.getReportDAO();
        this.sla = sla;
        this.numbers = new TicketNumberGenerator(factory.getTroubleTicketDAO());
        if (events == null) {
            throw new IllegalArgumentException("A ticket event publisher is required");
        }
        this.events = events;
    }

    /* ---------- Raising ---------- */

    @Override
    public TroubleTicket raise(UserSession actor, TicketRequest request) {
        TicketValidator.validateRaise(request);

        TelecomService service = services.findById(request.getServiceId())
                .orElseThrow(() -> new ResourceNotFoundException("telecom_services",
                        request.getServiceId()));

        // The customer is read from the service rather than taken from the
        // caller, so a ticket cannot be filed against one customer's line
        // under another customer's name.
        Long customerId = service.getCustomerId();
        AccessControl.requireCustomerAccess(actor, Permission.RAISE_TICKET, customerId);

        if (!service.isTicketable()) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Service " + service.getServiceCode() + " is "
                            + service.getServiceStatus().getDisplayName()
                            + ", so no ticket can be raised against it.");
        }

        Severity severity = severityFor(actor, request);
        Priority priority = priorityFor(actor, request, severity);

        for (int attempt = 1; attempt <= NUMBER_ATTEMPTS; attempt++) {
            String ticketNumber = numbers.next();
            try {
                return store(actor, request, service, customerId, priority, severity,
                        ticketNumber);
            } catch (DuplicateResourceException collision) {
                // Somebody outside this process took the number. Re-read the
                // table and ask for the next one.
                AppLogger.warn(TicketServiceImpl.class, "Ticket number " + ticketNumber
                        + " was taken; retrying (attempt " + attempt + ")");
                numbers.resync();
            }
        }
        throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE,
                "Could not obtain an unused ticket number after " + NUMBER_ATTEMPTS
                        + " attempts. Another process may be inserting tickets.");
    }

    private TroubleTicket store(UserSession actor, TicketRequest request,
                                TelecomService service, Long customerId, Priority priority,
                                Severity severity, String ticketNumber) {
        return TransactionTemplate.execute(context -> {
            TroubleTicket ticket = new TroubleTicket(ticketNumber, customerId,
                    service.getId(), request.getCategory(),
                    request.getDescription().trim(), priority, severity,
                    actor.getUsername());
            ticket.setStatus(TicketStatus.OPEN);
            ticket.setSlaStatus(SLAStatus.WITHIN_SLA);

            // Stamped here rather than left to the insert trigger so the
            // configured SLA clock decides, not the database's round the
            // clock arithmetic. The trigger remains the safety net for rows
            // arriving by any other route.
            sla.stampDeadlines(ticket);

            TroubleTicket stored = tickets.insert(ticket);

            history.insert(new TicketStatusHistory(stored.getId(), null, TicketStatus.OPEN,
                    actor.getUsername(), "Ticket raised: "
                    + shorten(request.getDescription())));

            events.publish(TicketEvent.raised(stored, actor,
                    request.getCategory().getDisplayName() + " on "
                            + service.getServiceCode() + ", " + priority.name()
                            + " priority, deadline " + stored.getSlaDeadline()));

            AppLogger.info(TicketServiceImpl.class, "Ticket " + ticketNumber
                    + " raised by '" + actor.getUsername() + "' for customer " + customerId);
            return stored;
        });
    }

    /**
     * Severity when the raiser did not say.
     *
     * <p>Derived from whether the category normally takes the service down
     * rather than degrading it. The rule matches the two tickets the seed
     * shows as customer raised: the network outage in section 5's sample
     * ticket arrives CRITICAL, and the call drop arrives MINOR. Tickets the
     * service desk raises vary, which is exactly why they are allowed to
     * say.</p>
     */
    private Severity severityFor(UserSession actor, TicketRequest request) {
        if (request.hasSeverity()) {
            requireStaffJudgement(actor, "severity");
            return request.getSeverity();
        }
        return request.getCategory().isServiceAffecting() ? Severity.CRITICAL : Severity.MINOR;
    }

    private Priority priorityFor(UserSession actor, TicketRequest request, Severity severity) {
        if (request.hasPriority()) {
            requireStaffJudgement(actor, "priority");
            return request.getPriority();
        }
        return severity.toDefaultPriority();
    }

    /**
     * Refuses rather than ignores. A customer who chose CRITICAL and was
     * quietly given MEDIUM would believe a promise nobody made.
     */
    private void requireStaffJudgement(UserSession actor, String what) {
        if (actor != null && actor.getRole() == Role.CUSTOMER) {
            throw new ValidationException("The " + what + " of a ticket is set by the "
                    + "service desk after assessing the fault, and cannot be chosen when "
                    + "raising it. Describe the impact in the description instead.");
        }
    }

    @Override
    public List<TelecomService> ticketableServicesFor(UserSession actor, Long customerId) {
        AccessControl.requireCustomerAccess(actor, Permission.RAISE_TICKET, customerId);
        return services.findTicketableByCustomerId(customerId);
    }

    /* ---------- Reading ---------- */

    @Override
    public TicketDetailDTO track(UserSession actor, String ticketNumber) {
        // The row is read first because the access check needs the customer
        // and engineer keys, which the view exposes only as codes. Two reads
        // for one screen, and the alternative is a view that leaks keys into
        // a presentation shape.
        TroubleTicket ticket = load(ticketNumber);
        AccessControl.requireTicketAccess(actor, viewPermissionFor(actor),
                ticket.getCustomerId(), ticket.getAssignedEngineerId());

        return reports.findTicketDetail(ticket.getTicketNumber())
                .orElseThrow(() -> new ResourceNotFoundException("vw_ticket_details",
                        ticketNumber));
    }

    @Override
    public TroubleTicket require(UserSession actor, String ticketNumber) {
        TroubleTicket ticket = load(ticketNumber);
        AccessControl.requireTicketAccess(actor, viewPermissionFor(actor),
                ticket.getCustomerId(), ticket.getAssignedEngineerId());
        return ticket;
    }

    @Override
    public List<TicketDetailDTO> listForCustomer(UserSession actor, Long customerId) {
        AccessControl.requireCustomerAccess(actor, viewPermissionFor(actor), customerId);
        return reports.findTicketDetailsForCustomer(customerId);
    }

    @Override
    public List<TicketDetailDTO> listAssignedTo(UserSession actor, Long engineerId) {
        // A customer holds neither of these, so asking for somebody's work
        // list is refused on the permission rather than on ownership.
        Permission needed = isEngineer(actor)
                ? Permission.VIEW_ASSIGNED_TICKETS
                : Permission.VIEW_ALL_TICKETS;
        AccessControl.require(actor, needed);

        if (isEngineer(actor) && !actor.isOwnEngineerWork(engineerId)) {
            throw new AuthorizationException("You may only list your own assigned tickets.");
        }
        return reports.findTicketDetailsForEngineer(engineerId);
    }

    private boolean isEngineer(UserSession actor) {
        return actor != null && actor.getRole() == Role.NETWORK_ENGINEER;
    }

    @Override
    public List<TroubleTicket> listOpen(UserSession actor) {
        AccessControl.require(actor, Permission.VIEW_ALL_TICKETS);
        return tickets.findOpen();
    }

    @Override
    public List<TicketStatusHistory> historyFor(UserSession actor, String ticketNumber) {
        TroubleTicket ticket = require(actor, ticketNumber);
        return history.findByTicketId(ticket.getId());
    }

    @Override
    public Set<TicketStatus> nextStatesFor(UserSession actor, String ticketNumber) {
        return TicketLifecycle.nextStatesFrom(require(actor, ticketNumber).getStatus());
    }

    /**
     * Which "view tickets" permission this role holds. Section 3 gives each
     * role a different one, so the operation has to pick the right one
     * before ownership can be checked.
     */
    private Permission viewPermissionFor(UserSession actor) {
        if (actor == null) {
            return Permission.VIEW_ALL_TICKETS;
        }
        switch (actor.getRole()) {
            case CUSTOMER:
                return Permission.VIEW_OWN_TICKETS;
            case NETWORK_ENGINEER:
                return Permission.VIEW_ASSIGNED_TICKETS;
            default:
                return Permission.VIEW_ALL_TICKETS;
        }
    }

    /* ---------- Working ---------- */

    @Override
    public void changeStatus(UserSession actor, String ticketNumber, TicketStatus target,
                             String remarks) {
        if (target == null) {
            throw new ValidationException("A target status is required");
        }
        if (!DIRECTLY_SETTABLE.contains(target)) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    describeWrongDoor(target));
        }
        TicketValidator.validateRemarks(remarks);

        TroubleTicket ticket = authorise(actor, ticketNumber, Permission.UPDATE_TICKET_STATUS);
        TicketStatus from = ticket.getStatus();
        TicketLifecycle.requireTransition(ticketNumber, from, target);

        TransactionTemplate.run(context -> {
            requireChanged(tickets.updateStatus(ticket.getId(), from, target), ticket, from);
            recordTransition(ticket, from, target, actor, remarks);
            events.publish(TicketEvent.statusChanged(reload(ticket), actor, from, target,
                    remarks));

            // Either of these moves means somebody has picked the ticket up:
            // started work on it, or gone back to the customer with a
            // question. That is what section 8's response window measures,
            // and stamping it here is what makes the response half of the
            // SLA real rather than merely configured.
            stampFirstResponse(ticket, actor);
        });
    }

    /**
     * Names the operation that reaches a state, rather than only refusing.
     */
    private String describeWrongDoor(TicketStatus target) {
        switch (target) {
            case ASSIGNED:
                return "A ticket reaches Assigned by being given an engineer. "
                        + "Use the assignment operation.";
            case ESCALATED:
                return "A ticket reaches Escalated through escalation, which also "
                        + "records the level it went to.";
            case RESOLVED:
                return "Resolving a ticket also records the resolution code, the root "
                        + "cause and the fix. Use the resolve operation.";
            case CLOSED:
                return "Use the close operation, which is only available once the ticket "
                        + "has been resolved.";
            case CANCELLED:
                return "Use the cancel operation, which requires a reason.";
            default:
                return "A ticket cannot be moved directly to " + target.getDisplayName() + ".";
        }
    }

    @Override
    public void recordDiagnosis(UserSession actor, String ticketNumber, String rootCause) {
        TicketValidator.validateDiagnosis(rootCause);
        TroubleTicket ticket = authorise(actor, ticketNumber, Permission.RECORD_DIAGNOSIS);

        if (ticket.getStatus().isFinished()) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Ticket " + ticketNumber + " is " + ticket.getStatus().getDisplayName()
                            + ". A diagnosis can only be recorded while it is being worked.");
        }
        String text = rootCause.trim();

        TransactionTemplate.run(context -> {
            if (!tickets.updateRootCause(ticket.getId(), text)) {
                throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                        "Ticket " + ticketNumber + " was finished by somebody else while "
                                + "the diagnosis was being entered.");
            }
            events.publish(TicketEvent.diagnosisRecorded(reload(ticket), actor,
                    ticket.getRootCause(), text));
            stampFirstResponse(ticket, actor);
        });
    }

    @Override
    public void resolve(UserSession actor, String ticketNumber, ResolutionCode code,
                        String rootCause, String resolution) {
        TicketValidator.validateResolution(code, rootCause, resolution);
        TroubleTicket ticket = authorise(actor, ticketNumber, Permission.RESOLVE_TICKET);
        TicketStatus from = ticket.getStatus();
        TicketLifecycle.requireTransition(ticketNumber, from, TicketStatus.RESOLVED);

        TransactionTemplate.run(context -> {
            requireChanged(tickets.resolve(ticket.getId(), from, code, rootCause.trim(),
                    resolution.trim()), ticket, from);
            recordTransition(ticket, from, TicketStatus.RESOLVED, actor,
                    code.getDisplayName() + ": " + shorten(resolution));
            events.publish(TicketEvent.resolved(reload(ticket), actor, from,
                    code.name() + " - " + rootCause));
            stampFirstResponse(ticket, actor);
            refreshSlaStanding(ticket);
        });
    }

    /**
     * Recomputes the stored SLA standing from the row as it now is.
     *
     * <p>The column exists so a report does not have to recompute history,
     * and a background monitor keeps it current for tickets still running.
     * Refreshing it here covers the moments where waiting for the monitor
     * would leave it wrong: on resolution, because that is when the answer
     * becomes final and never changes again, and after a priority change or
     * a reopen, because both alter what the ticket is being judged
     * against.</p>
     */
    private void refreshSlaStanding(TroubleTicket ticket) {
        TroubleTicket current = tickets.getById(ticket.getId());
        SlaEvaluation verdict = sla.evaluate(current);
        tickets.updateSlaStatus(current.getId(), verdict.getLiveStatus());
    }

    @Override
    public void close(UserSession actor, String ticketNumber, String remarks) {
        TicketValidator.validateRemarks(remarks);
        TroubleTicket ticket = authorise(actor, ticketNumber, Permission.CLOSE_TICKET);
        TicketStatus from = ticket.getStatus();
        TicketLifecycle.requireTransition(ticketNumber, from, TicketStatus.CLOSED);

        TransactionTemplate.run(context -> {
            requireChanged(tickets.close(ticket.getId()), ticket, from);
            recordTransition(ticket, from, TicketStatus.CLOSED, actor, remarks);
            events.publish(TicketEvent.closed(reload(ticket), actor, from, remarks));
        });
    }

    @Override
    public void cancel(UserSession actor, String ticketNumber, String reason) {
        TicketValidator.validateReason("The ticket could not be cancelled", "Reason", reason);
        TroubleTicket ticket = authorise(actor, ticketNumber, Permission.CANCEL_TICKET);
        TicketStatus from = ticket.getStatus();
        TicketLifecycle.requireTransition(ticketNumber, from, TicketStatus.CANCELLED);

        TransactionTemplate.run(context -> {
            requireChanged(tickets.updateStatus(ticket.getId(), from, TicketStatus.CANCELLED),
                    ticket, from);
            recordTransition(ticket, from, TicketStatus.CANCELLED, actor, reason);
            events.publish(TicketEvent.cancelled(reload(ticket), actor, from, reason));
            // A ticket that should not have been raised is not one the
            // operator failed to fix in time, so a cancelled ticket that had
            // already run past its deadline stops reading as a breach.
            refreshSlaStanding(ticket);
        });
    }

    @Override
    public void reopen(UserSession actor, String ticketNumber, String reason) {
        TicketValidator.validateReason("The ticket could not be reopened", "Reason", reason);
        TroubleTicket ticket = authorise(actor, ticketNumber, Permission.UPDATE_TICKET_STATUS);
        TicketStatus from = ticket.getStatus();
        TicketLifecycle.requireTransition(ticketNumber, from, TicketStatus.IN_PROGRESS);

        // Kept before the write clears it, so the trail still says what was
        // tried and did not work.
        String discarded = describeDiscardedResolution(ticket);

        TransactionTemplate.run(context -> {
            requireChanged(tickets.reopen(ticket.getId(), from, TicketStatus.IN_PROGRESS),
                    ticket, from);
            recordTransition(ticket, from, TicketStatus.IN_PROGRESS, actor,
                    shorten("Reopened: " + reason + ". Previous " + discarded));
            events.publish(TicketEvent.reopened(reload(ticket), actor, from,
                    reason + ". Cleared " + discarded));
            // The original deadline stands. The promise was to fix the
            // fault, and a ticket back in progress is not fixed, so a
            // reopened ticket that has run past its deadline has breached
            // it. Recomputed here because clearing the resolution date
            // restarts the elapsed time the verdict is based on.
            refreshSlaStanding(ticket);
        });
    }

    private String describeDiscardedResolution(TroubleTicket ticket) {
        StringBuilder builder = new StringBuilder("resolution");
        if (ticket.getResolutionCode() != null) {
            builder.append(" (").append(ticket.getResolutionCode().name()).append(")");
        }
        if (!Validators.isBlank(ticket.getResolution())) {
            builder.append(": ").append(ticket.getResolution().trim());
        }
        return builder.toString();
    }

    @Override
    public void updatePriority(UserSession actor, String ticketNumber, Priority priority,
                               String reason) {
        if (priority == null) {
            throw new ValidationException("A priority is required");
        }
        TicketValidator.validateReason("The priority could not be changed", "Reason", reason);
        TroubleTicket ticket = authorise(actor, ticketNumber, Permission.UPDATE_TICKET_PRIORITY);

        if (ticket.getStatus().isFinished()) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Ticket " + ticketNumber + " is " + ticket.getStatus().getDisplayName()
                            + ". The priority it was handled at is part of the record.");
        }
        Priority from = ticket.getPriority();
        if (from == priority) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Ticket " + ticketNumber + " is already " + priority.getDisplayName()
                            + " priority.");
        }

        TransactionTemplate.run(context -> {
            if (!tickets.updatePriority(ticket.getId(), priority)) {
                throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                        "Ticket " + ticketNumber + " was finished by somebody else while "
                                + "the priority was being changed.");
            }
            // The update trigger already moved the deadlines using the
            // database's round the clock arithmetic. This rewrites them
            // using the clock configured for the new band, which is the same
            // answer for a continuous clock and the right one otherwise.
            sla.recalculateDeadlines(ticket.getId());
            events.publish(TicketEvent.priorityChanged(reload(ticket), actor, from, priority,
                    reason));
            refreshSlaStanding(ticket);
        });
        AppLogger.info(TicketServiceImpl.class, "Ticket " + ticketNumber + " moved from "
                + from + " to " + priority + " priority by '" + actor.getUsername() + "'");
    }

    @Override
    public boolean recordFirstResponse(UserSession actor, String ticketNumber) {
        TroubleTicket ticket = authorise(actor, ticketNumber, Permission.UPDATE_TICKET_STATUS);
        if (ticket.getFirstResponseDate() != null) {
            return false;
        }
        return TransactionTemplate.execute(context -> stampFirstResponse(ticket, actor));
    }

    /**
     * Records the first response if there is not one already. The DAO's
     * {@code IS NULL} guard is what makes it the first rather than the
     * latest, so calling this from several places is safe.
     */
    private boolean stampFirstResponse(TroubleTicket ticket, UserSession actor) {
        LocalDateTime respondedAt = LocalDateTime.now().withNano(0);
        if (!tickets.recordFirstResponse(ticket.getId(), respondedAt)) {
            return false;
        }
        events.publish(TicketEvent.firstResponse(reload(ticket), actor, respondedAt));
        return true;
    }

    /* ---------- Feedback ---------- */

    @Override
    public Feedback submitFeedback(UserSession actor, String ticketNumber, int rating,
                                   String comments) {
        TicketValidator.validateFeedback(rating, comments);
        TroubleTicket ticket = load(ticketNumber);
        AccessControl.requireTicketAccess(actor, Permission.SUBMIT_FEEDBACK,
                ticket.getCustomerId(), ticket.getAssignedEngineerId());

        // Section 13 offers feedback alongside tracking a ticket but does
        // not say when. Before the ticket has been dealt with there is
        // nothing to rate, and section 20's customer satisfaction figure is
        // about how tickets were handled, so it has to be about handled
        // tickets.
        if (ticket.getStatus() != TicketStatus.RESOLVED
                && ticket.getStatus() != TicketStatus.CLOSED) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Ticket " + ticketNumber + " is " + ticket.getStatus().getDisplayName()
                            + ". Feedback can be given once it has been resolved.");
        }
        if (feedback.findByTicketId(ticket.getId()).isPresent()) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE,
                    "Feedback has already been given for ticket " + ticketNumber + ".");
        }

        return TransactionTemplate.execute(context -> {
            Feedback stored = feedback.insert(new Feedback(ticket.getId(),
                    ticket.getCustomerId(), rating, Validators.trimToNull(comments)));
            // The ticket itself is untouched by feedback, so this is the row
            // as it stands rather than one that needs re-reading.
            events.publish(TicketEvent.feedbackSubmitted(ticket, actor, rating, comments));
            return stored;
        });
    }

    @Override
    public Optional<Feedback> feedbackFor(UserSession actor, String ticketNumber) {
        return feedback.findByTicketId(require(actor, ticketNumber).getId());
    }

    /* ---------- Shared ---------- */

    private TroubleTicket load(String ticketNumber) {
        if (Validators.isBlank(ticketNumber)) {
            throw new ValidationException("A ticket number is required");
        }
        String trimmed = ticketNumber.trim();
        return tickets.findByTicketNumber(trimmed)
                .orElseThrow(() -> new ResourceNotFoundException("trouble_tickets", trimmed));
    }

    /**
     * Loads the ticket and checks the caller may act on it, which for a
     * customer means their own and for an engineer means theirs.
     */
    private TroubleTicket authorise(UserSession actor, String ticketNumber,
                                    Permission permission) {
        TroubleTicket ticket = load(ticketNumber);
        AccessControl.requireTicketAccess(actor, permission, ticket.getCustomerId(),
                ticket.getAssignedEngineerId());
        return ticket;
    }

    /**
     * Turns a guarded update that changed nothing into an explanation.
     *
     * <p>Reached only when the ticket moved between the read and the write,
     * so the message says so rather than repeating the transition rules.</p>
     */
    private void requireChanged(boolean changed, TroubleTicket ticket, TicketStatus expected) {
        if (changed) {
            return;
        }
        TicketStatus actual = tickets.findById(ticket.getId())
                .map(TroubleTicket::getStatus)
                .orElse(null);
        throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                "Ticket " + ticket.getTicketNumber() + " was " + expected.getDisplayName()
                        + " when this change was prepared but is now "
                        + (actual == null ? "gone" : actual.getDisplayName())
                        + ". Look at it again before retrying.");
    }

    private void recordTransition(TroubleTicket ticket, TicketStatus from, TicketStatus to,
                                  UserSession actor, String remarks) {
        history.insert(new TicketStatusHistory(ticket.getId(), from, to, actor.getUsername(),
                shorten(remarks)));
    }

    /**
     * Reads the ticket back after a write.
     *
     * <p>An event carries the row as it stands once the change has been
     * made, because a listener may read any part of it: the notification for
     * a resolved ticket quotes the resolution code, which only exists on the
     * row after the update. The in-memory ticket the method has been working
     * with still holds the values it read at the start, so it would describe
     * the ticket as it was.</p>
     *
     * <p>The cost is a primary key lookup inside a transaction that has
     * already written, which is the cheapest read there is.</p>
     */
    private TroubleTicket reload(TroubleTicket ticket) {
        return tickets.getById(ticket.getId());
    }

    /**
     * Fits free text into the 500 character remarks column, or returns null
     * so it holds NULL rather than a string of spaces.
     */
    private String shorten(String text) {
        return Validators.shorten(text, TicketValidator.REMARKS_MAX);
    }
}
