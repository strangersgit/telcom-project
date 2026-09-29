package com.amdocs.telecom.service.impl;

import com.amdocs.telecom.dao.CustomerDAO;
import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.NetworkEngineerDAO;
import com.amdocs.telecom.dao.ReportDAO;
import com.amdocs.telecom.dao.TicketStatusHistoryDAO;
import com.amdocs.telecom.dao.TransactionTemplate;
import com.amdocs.telecom.dao.TroubleTicketDAO;
import com.amdocs.telecom.dto.EngineerRecommendationDTO;
import com.amdocs.telecom.exception.BusinessException;
import com.amdocs.telecom.exception.ErrorCode;
import com.amdocs.telecom.exception.ResourceNotFoundException;
import com.amdocs.telecom.exception.ValidationException;
import com.amdocs.telecom.model.Customer;
import com.amdocs.telecom.model.NetworkEngineer;
import com.amdocs.telecom.model.TicketStatusHistory;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.model.enums.Specialization;
import com.amdocs.telecom.model.enums.TicketStatus;
import com.amdocs.telecom.security.AccessControl;
import com.amdocs.telecom.security.Permission;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.EngineerAssignmentService;
import com.amdocs.telecom.service.assignment.AssignmentResult;
import com.amdocs.telecom.service.assignment.EngineerMatch;
import com.amdocs.telecom.service.assignment.EngineerRecommender;
import com.amdocs.telecom.service.assignment.MatchTier;
import com.amdocs.telecom.service.event.TicketEvent;
import com.amdocs.telecom.service.event.TicketEventPublisher;
import com.amdocs.telecom.util.AppLogger;
import com.amdocs.telecom.validation.TicketLifecycle;
import com.amdocs.telecom.validation.TicketValidator;
import com.amdocs.telecom.validation.ValidationResult;
import com.amdocs.telecom.validation.Validators;

import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Recommends engineers and hands tickets over to them.
 *
 * <h3>The section 19 transaction</h3>
 *
 * <p>Section 19 sets out assignment as nine steps ending in a commit, with
 * any failure rolling back. {@link #handOver} is those steps in that order,
 * and each one is named in a comment so the correspondence can be checked
 * rather than taken on trust. Two of them, the notification and the audit
 * record, are not written here at all: they are what the observers from
 * {@link com.amdocs.telecom.service.event} do when the assignment is
 * announced. They still happen inside this transaction, so the document's
 * "any failure to ROLLBACK" holds for them too.</p>
 *
 * <h3>Validation happens twice, on purpose</h3>
 *
 * <p>Every step that checks something also has that check repeated in the
 * WHERE clause of the statement that acts on it. Reading that an engineer
 * has a free slot and then taking it are two operations, and another
 * operator can assign work in between. So the read decides whether to try
 * and gives the caller a clear reason when not, while the guarded update
 * decides whether it actually happened. A guard that changes no rows becomes
 * a refusal here, never a silent overwrite.</p>
 *
 * <h3>Where the savepoint earns its place</h3>
 *
 * <p>A single assignment needs no savepoint: it either happens completely or
 * not at all, which is what the surrounding transaction already gives.
 * {@link #sweepQueue} is different. It walks the queue assigning what it
 * can, and a critical ticket that no engineer can take must not abandon the
 * nineteen behind it. Each attempt gets its own savepoint, so a refusal
 * unwinds that ticket's work and leaves everything already assigned intact.
 * That is the case the JDBC savepoint exists for.</p>
 */
public final class EngineerAssignmentServiceImpl implements EngineerAssignmentService {

    /**
     * How many tickets one sweep will consider. A cap rather than the whole
     * queue, because the sweep holds a transaction open while it runs.
     */
    public static final int MAX_SWEEP = 50;

    private static final int DEFAULT_SWEEP = 20;

    private final TroubleTicketDAO tickets;
    private final NetworkEngineerDAO engineers;
    private final CustomerDAO customers;
    private final TicketStatusHistoryDAO history;
    private final ReportDAO reports;
    private final EngineerRecommender recommender;
    private final TicketEventPublisher events;

    public EngineerAssignmentServiceImpl() {
        this(DAOFactory.getInstance(), TicketEventPublisher.getInstance());
    }

    public EngineerAssignmentServiceImpl(DAOFactory factory, TicketEventPublisher events) {
        this.tickets = factory.getTroubleTicketDAO();
        this.engineers = factory.getNetworkEngineerDAO();
        this.customers = factory.getCustomerDAO();
        this.history = factory.getTicketStatusHistoryDAO();
        this.reports = factory.getReportDAO();
        this.recommender = new EngineerRecommender();
        if (events == null) {
            throw new IllegalArgumentException("A ticket event publisher is required");
        }
        this.events = events;
    }

    /* ---------- Recommending ---------- */

    @Override
    public List<EngineerMatch> recommend(UserSession actor, String ticketNumber, int limit) {
        TroubleTicket ticket = authorise(actor, ticketNumber);
        return recommender.shortlist(engineers.findAll(), skillFor(ticket), regionOf(ticket),
                limit);
    }

    @Override
    public Optional<EngineerMatch> recommendBest(UserSession actor, String ticketNumber) {
        TroubleTicket ticket = authorise(actor, ticketNumber);
        return recommender.best(engineers.findAll(), skillFor(ticket), regionOf(ticket));
    }

    @Override
    public List<EngineerRecommendationDTO> recommendViaProcedure(UserSession actor,
                                                                 String ticketNumber, int limit) {
        TroubleTicket ticket = authorise(actor, ticketNumber);
        return reports.recommendEngineers(skillFor(ticket), regionOf(ticket),
                limit <= 0 ? EngineerRecommender.DEFAULT_SHORTLIST : limit);
    }

    @Override
    public List<NetworkEngineer> leastBusySpecialists(UserSession actor, String ticketNumber,
                                                      int count) {
        TroubleTicket ticket = authorise(actor, ticketNumber);
        return recommender.lowestWorkload(engineers.findAll(), skillFor(ticket), count);
    }

    @Override
    public List<TroubleTicket> unassignedQueue(UserSession actor) {
        AccessControl.require(actor, Permission.ASSIGN_ENGINEER);
        return tickets.findUnassigned();
    }

    /* ---------- Assigning ---------- */

    @Override
    public AssignmentResult assign(UserSession actor, String ticketNumber, String employeeCode) {
        TroubleTicket ticket = authorise(actor, ticketNumber);
        NetworkEngineer engineer = requireEngineer(employeeCode);
        return TransactionTemplate.execute(context ->
                handOver(actor, ticket, engineer, null, null));
    }

    @Override
    public AssignmentResult autoAssign(UserSession actor, String ticketNumber) {
        TroubleTicket ticket = authorise(actor, ticketNumber);
        return TransactionTemplate.execute(context -> assignAutomatically(actor, ticket));
    }

    /**
     * Picks an engineer and hands the ticket over.
     *
     * <p>Shared by {@link #autoAssign} and {@link #sweepQueue}, which is why
     * it takes a loaded ticket and runs no transaction of its own.</p>
     */
    private AssignmentResult assignAutomatically(UserSession actor, TroubleTicket ticket) {
        EngineerMatch chosen = recommender
                .best(engineers.findAll(), skillFor(ticket), regionOf(ticket))
                .orElseThrow(() -> new BusinessException(ErrorCode.NO_ENGINEER_AVAILABLE,
                        describeEmptyRoster(ticket)));
        return handOver(actor, ticket, chosen.getEngineer(), chosen.getTier(), null);
    }

    @Override
    public AssignmentResult reassign(UserSession actor, String ticketNumber, String employeeCode,
                                     String reason) {
        TicketValidator.validateReason("The ticket could not be reassigned", "Reason", reason);
        TroubleTicket ticket = authorise(actor, ticketNumber);
        NetworkEngineer incoming = requireEngineer(employeeCode);

        if (ticket.getStatus().isFinished()) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Ticket " + ticket.getTicketNumber() + " is "
                            + ticket.getStatus().getDisplayName()
                            + ". Who worked on it is part of the record.");
        }
        if (ticket.getAssignedEngineerId() == null) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Ticket " + ticket.getTicketNumber() + " has nobody on it yet. "
                            + "Use the assignment operation rather than reassignment.");
        }
        NetworkEngineer outgoing = engineers.getById(ticket.getAssignedEngineerId());
        if (outgoing.getId().equals(incoming.getId())) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Ticket " + ticket.getTicketNumber() + " is already with "
                            + incoming.getEmployeeCode() + ".");
        }
        requireCanTakeWork(incoming);

        return TransactionTemplate.execute(context -> {
            // The outgoing engineer's slot is released first so an engineer
            // at capacity can hand a ticket to somebody and immediately be
            // handed one back without appearing to exceed their limit.
            if (!engineers.decrementWorkload(outgoing.getId())) {
                AppLogger.warn(EngineerAssignmentServiceImpl.class, "Engineer "
                        + outgoing.getEmployeeCode() + " showed no active tickets while "
                        + ticket.getTicketNumber() + " was being moved away");
            }
            if (!engineers.incrementWorkload(incoming.getId())) {
                throw new BusinessException(ErrorCode.ENGINEER_UNAVAILABLE,
                        "Engineer " + incoming.getEmployeeCode()
                                + " filled up before the ticket could be moved across.");
            }
            if (!tickets.reassignEngineer(ticket.getId(), incoming.getId(), outgoing.getId())) {
                throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                        "Ticket " + ticket.getTicketNumber() + " was with "
                                + outgoing.getEmployeeCode() + " when this change was prepared "
                                + "but has since moved. Look at it again before retrying.");
            }
            engineers.refreshAvailability(outgoing.getId());
            engineers.refreshAvailability(incoming.getId());

            TicketStatus unchanged = ticket.getStatus();
            history.insert(new TicketStatusHistory(ticket.getId(), unchanged, unchanged,
                    actor.getUsername(), Validators.shorten("Reassigned from "
                    + outgoing.getEmployeeCode() + " to " + incoming.getEmployeeCode()
                    + ": " + reason, TicketValidator.REMARKS_MAX)));

            events.publish(TicketEvent.assigned(reload(ticket), actor,
                    outgoing.getEmployeeCode(), incoming.getEmployeeCode(), reason));

            AppLogger.info(EngineerAssignmentServiceImpl.class, "Ticket "
                    + ticket.getTicketNumber() + " moved from " + outgoing.getEmployeeCode()
                    + " to " + incoming.getEmployeeCode() + " by '" + actor.getUsername() + "'");
            return AssignmentResult.reassigned(ticket.getTicketNumber(),
                    outgoing.getEmployeeCode(), incoming.getEmployeeCode());
        });
    }

    @Override
    public List<AssignmentResult> sweepQueue(UserSession actor, int limit) {
        AccessControl.require(actor, Permission.ASSIGN_ENGINEER);
        List<TroubleTicket> queue = tickets.findUnassigned().stream()
                .limit(boundedSweep(limit))
                .collect(Collectors.toList());
        if (queue.isEmpty()) {
            return new ArrayList<>();
        }

        return TransactionTemplate.execute(context -> {
            List<AssignmentResult> outcomes = new ArrayList<>(queue.size());
            for (TroubleTicket ticket : queue) {
                Savepoint marker = context.savepoint("sweep_" + ticket.getId());
                try {
                    outcomes.add(assignAutomatically(actor, ticket));
                    context.release(marker);
                } catch (BusinessException refused) {
                    // Only a business refusal is survivable. A database
                    // failure arrives as DataAccessException and is left to
                    // propagate, because at that point nothing about the
                    // transaction can be trusted.
                    context.rollbackTo(marker);
                    outcomes.add(AssignmentResult.skipped(ticket.getTicketNumber(),
                            refused.getMessage()));
                }
            }
            AppLogger.info(EngineerAssignmentServiceImpl.class, "Queue sweep by '"
                    + actor.getUsername() + "' assigned " + countAssigned(outcomes)
                    + " of " + outcomes.size() + " ticket(s) considered");
            return outcomes;
        });
    }

    @Override
    public ReportDAO.ProcedureOutcome assignViaProcedure(UserSession actor, String ticketNumber,
                                                          String employeeCode) {
        TroubleTicket ticket = authorise(actor, ticketNumber);
        NetworkEngineer engineer = requireEngineer(employeeCode);
        return reports.assignEngineerViaProcedure(ticket.getId(), engineer.getId(),
                actor.getUsername());
    }

    /* ---------- The section 19 sequence ---------- */

    /**
     * Assigns a ticket that currently has nobody, as the nine steps of
     * section 19.
     *
     * @param tier   how the engineer was found, or null when a person named
     *               them
     * @param remark extra wording for the trail, or null
     */
    private AssignmentResult handOver(UserSession actor, TroubleTicket ticket,
                                      NetworkEngineer engineer, MatchTier tier, String remark) {
        // Step 1: validate the ticket. Whether OPEN may become ASSIGNED is
        // the transition graph's question, not this method's, so it is asked
        // rather than re-decided.
        if (ticket.getAssignedEngineerId() != null) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Ticket " + ticket.getTicketNumber() + " is already with engineer "
                            + codeOf(ticket.getAssignedEngineerId())
                            + ". Use reassignment to move it.");
        }
        TicketStatus from = ticket.getStatus();
        TicketLifecycle.requireTransition(ticket.getTicketNumber(), from, TicketStatus.ASSIGNED);

        // Step 2 and 3: validate the engineer and check their availability.
        // Both were established by the caller loading the engineer; this is
        // the check that turns a stale roster into a clear refusal.
        requireCanTakeWork(engineer);

        // Step 4: assign the engineer, meaning claim one of their slots. The
        // capacity test is inside the statement, so two assignments racing
        // for a last free slot cannot both win.
        if (!engineers.incrementWorkload(engineer.getId())) {
            throw new BusinessException(ErrorCode.ENGINEER_UNAVAILABLE,
                    "Engineer " + engineer.getEmployeeCode() + " reached capacity before the "
                            + "ticket could be handed over.");
        }

        // Step 5: update the ticket. Guarded on the status read above and on
        // the ticket still being unassigned.
        if (!tickets.assignEngineer(ticket.getId(), engineer.getId(), from)) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Ticket " + ticket.getTicketNumber() + " was " + from.getDisplayName()
                            + " and unassigned when this change was prepared, but has since "
                            + "moved. Look at it again before retrying.");
        }
        engineers.refreshAvailability(engineer.getId());

        // Step 6: the status history entry, which is ticket data and so is
        // written here rather than by an observer.
        history.insert(new TicketStatusHistory(ticket.getId(), from, TicketStatus.ASSIGNED,
                actor.getUsername(), Validators.shorten(describeHandover(engineer, tier, remark),
                TicketValidator.REMARKS_MAX)));

        // Steps 7 and 8: the notification and the audit record. Both are
        // consequences of the announcement rather than work this method
        // does, and both run inside this transaction.
        events.publish(TicketEvent.assigned(reload(ticket), actor, null,
                engineer.getEmployeeCode(), tier == null ? remark : tier.getReason()));

        // Step 9 is the commit, which belongs to whoever opened the
        // transaction: a single assignment commits on return, a sweep
        // commits once at the end.
        AppLogger.info(EngineerAssignmentServiceImpl.class, "Ticket " + ticket.getTicketNumber()
                + " assigned to " + engineer.getEmployeeCode() + " by '" + actor.getUsername()
                + "'" + (tier == null ? "" : " (" + tier.getReason() + ")"));
        return AssignmentResult.assigned(ticket.getTicketNumber(), engineer.getEmployeeCode(),
                tier);
    }

    private String describeHandover(NetworkEngineer engineer, MatchTier tier, String remark) {
        StringBuilder text = new StringBuilder("Assigned to ")
                .append(engineer.getEmployeeCode());
        if (tier != null) {
            text.append(" (").append(tier.getReason()).append(')');
        }
        if (!Validators.isBlank(remark)) {
            text.append(": ").append(remark.trim());
        }
        return text.toString();
    }

    /* ---------- Shared ---------- */

    /**
     * Loads the ticket and checks the caller may assign work at all.
     *
     * <p>Unlike the ticket service there is no per-ticket ownership test
     * here. {@link Permission#ASSIGN_ENGINEER} belongs to the service desk
     * and the network manager, neither of whom is tied to a customer or to
     * an engineer's own queue, so holding the permission is the whole
     * test.</p>
     */
    private TroubleTicket authorise(UserSession actor, String ticketNumber) {
        AccessControl.require(actor, Permission.ASSIGN_ENGINEER);
        if (Validators.isBlank(ticketNumber)) {
            throw new ValidationException("A ticket number is required");
        }
        String trimmed = ticketNumber.trim();
        return tickets.findByTicketNumber(trimmed)
                .orElseThrow(() -> new ResourceNotFoundException("trouble_tickets", trimmed));
    }

    private NetworkEngineer requireEngineer(String employeeCode) {
        ValidationResult result = ValidationResult.forOperation("Find engineer");
        Validators.requireText(result, "Employee code", employeeCode, 1, 20);
        result.throwIfInvalid();

        String trimmed = employeeCode.trim();
        return engineers.findByEmployeeCode(trimmed)
                .orElseThrow(() -> new ResourceNotFoundException("network_engineers", trimmed));
    }

    /**
     * The availability gate of section 7, as a refusal that says which of
     * the two reasons applies.
     */
    private void requireCanTakeWork(NetworkEngineer engineer) {
        if (engineer.canAcceptWork()) {
            return;
        }
        if (engineer.getAvailability() == null || !engineer.getAvailability().canAcceptWork()) {
            throw new BusinessException(ErrorCode.ENGINEER_UNAVAILABLE,
                    "Engineer " + engineer.getEmployeeCode() + " is "
                            + (engineer.getAvailability() == null
                            ? "of unknown availability"
                            : engineer.getAvailability().getDisplayName().toLowerCase()) + ".");
        }
        throw new BusinessException(ErrorCode.ENGINEER_UNAVAILABLE,
                "Engineer " + engineer.getEmployeeCode() + " already holds "
                        + engineer.getActiveTicketCount() + " of a maximum "
                        + engineer.getMaxTicketCapacity() + " tickets.");
    }

    /**
     * The skill section 7 says should be matched, which comes from the
     * incident category rather than being chosen per ticket.
     */
    private Specialization skillFor(TroubleTicket ticket) {
        if (ticket.getCategory() == null) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Ticket " + ticket.getTicketNumber() + " has no category, so no "
                            + "specialization can be matched to it.");
        }
        return ticket.getCategory().getPreferredSpecialization();
    }

    /**
     * The ticket's region, which it inherits from the customer.
     *
     * <p>Tickets carry no region of their own; a fault belongs to wherever
     * the customer is. A customer who has somehow lost their region gives
     * null, which the recommender reads as "region does not matter" rather
     * than as an error, because a ticket still has to reach somebody.</p>
     */
    private Region regionOf(TroubleTicket ticket) {
        return customers.findById(ticket.getCustomerId())
                .map(Customer::getRegion)
                .orElse(null);
    }

    /**
     * Why nobody could be found, in terms the operator can act on.
     */
    private String describeEmptyRoster(TroubleTicket ticket) {
        Specialization required = skillFor(ticket);
        Region region = regionOf(ticket);
        long holdingSkill = engineers.findBySpecialization(required).size();
        return "No engineer can take " + ticket.getTicketNumber() + " automatically. It needs "
                + required.getDisplayName() + (region == null ? "" : " in " + region.getDisplayName())
                + "; of the " + holdingSkill + " engineer(s) with that skill, none is on duty "
                + "with spare capacity. Assign somebody by name, or free up capacity first.";
    }

    private String codeOf(Long engineerId) {
        return engineers.findById(engineerId)
                .map(NetworkEngineer::getEmployeeCode)
                .orElse("(unknown)");
    }

    /**
     * Reads the ticket back so the event carries the row as it now is,
     * holding the engineer rather than the null it held a moment ago.
     */
    private TroubleTicket reload(TroubleTicket ticket) {
        return tickets.getById(ticket.getId());
    }

    private static long countAssigned(List<AssignmentResult> outcomes) {
        return outcomes.stream().filter(AssignmentResult::isAssigned).count();
    }

    private static long boundedSweep(int requested) {
        if (requested <= 0) {
            return DEFAULT_SWEEP;
        }
        return Math.min(requested, MAX_SWEEP);
    }
}
