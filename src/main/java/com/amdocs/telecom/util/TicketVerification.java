package com.amdocs.telecom.util;

import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.ReportDAO;
import com.amdocs.telecom.dao.TransactionTemplate;
import com.amdocs.telecom.dao.TroubleTicketDAO;
import com.amdocs.telecom.dto.TicketDetailDTO;
import com.amdocs.telecom.dto.TicketRequest;
import com.amdocs.telecom.exception.ValidationException;
import com.amdocs.telecom.model.Feedback;
import com.amdocs.telecom.model.TelecomService;
import com.amdocs.telecom.model.TicketStatusHistory;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.UserAccount;
import com.amdocs.telecom.model.enums.IncidentCategory;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.model.enums.ResolutionCode;
import com.amdocs.telecom.model.enums.Role;
import com.amdocs.telecom.model.enums.ServiceStatus;
import com.amdocs.telecom.model.enums.Severity;
import com.amdocs.telecom.model.enums.TicketStatus;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.TicketService;
import com.amdocs.telecom.service.event.TicketEventPublisher;
import com.amdocs.telecom.service.impl.SlaServiceImpl;
import com.amdocs.telecom.service.impl.TicketNumberGenerator;
import com.amdocs.telecom.service.impl.TicketServiceImpl;
import com.amdocs.telecom.validation.TicketLifecycle;
import com.amdocs.telecom.validation.TicketValidator;
import com.amdocs.telecom.validation.ValidationResult;
import com.amdocs.telecom.validation.Validators;

import java.sql.Savepoint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Exercises the ticket lifecycle against the seeded database.
 *
 * <p>The pure parts, meaning the validators and the transition graph, are
 * checked on their own where a wrong answer is unambiguous. Everything else
 * runs a ticket through its whole life: raised, assigned, worked, diagnosed,
 * resolved, rated, reopened, resolved again and closed, with each step
 * confirming both the row and the trail it should have left.</p>
 *
 * <p>Nothing is left behind. Every write happens inside one transaction that
 * is rolled back to a savepoint, which is also how a service can be
 * terminated and a priority retuned without the seed being disturbed.</p>
 */
public final class TicketVerification {

    private TicketVerification() {
        throw new AssertionError("TicketVerification is not instantiable");
    }

    /** A seeded ticket, read only, used to borrow real keys from. */
    private static final String TEMPLATE_TICKET = "TT-2026-004521";

    /** A seeded ticket belonging to a different customer. */
    private static final String OTHER_CUSTOMER_TICKET = "TT-2026-004522";

    /** A seeded ticket that already has an engineer. */
    private static final String ASSIGNED_TICKET = "TT-2026-004523";

    /** A seeded ticket that is already finished, for the refusal path. */
    private static final String CLOSED_TICKET = "TT-2026-004511";

    private static final String DESCRIPTION =
            "Verification probe: intermittent packet loss on the access link";

    private static int checksRun;
    private static int checksFailed;

    /** Ticket numbers issued during the run, so strays can be swept up. */
    private static final List<String> ISSUED = new ArrayList<String>();

    /**
     * Runs every check and prints a report.
     *
     * @return 0 when everything passed, 1 otherwise
     */
    public static int execute() {
        checksRun = 0;
        checksFailed = 0;
        ISSUED.clear();

        DAOFactory factory = DAOFactory.getInstance();
        TicketService service = new TicketServiceImpl(factory, new SlaServiceImpl(),
                TicketEventPublisher.getInstance());

        System.out.println("  Ticket lifecycle verification");
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println();

        try {
            verifyValidationAccumulator();
            verifyFieldValidators();
            verifyTransitionGraph();
            verifyNumbering(factory);
            verifyLifecycle(factory, service);
            verifyStoredProcedure(factory, service);
        } catch (RuntimeException failure) {
            checksFailed++;
            System.out.println("  Verification aborted: " + failure);
            AppLogger.error(TicketVerification.class, "Ticket verification aborted", failure);
        } finally {
            int strays = removeIssuedTickets(factory.getTroubleTicketDAO());
            if (strays > 0) {
                System.out.println();
                System.out.println("  Removed " + strays + " stray probe ticket(s)");
            }
        }

        System.out.println();
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println("  " + (checksRun - checksFailed) + " of " + checksRun
                + " checks passed");
        System.out.println();

        if (checksFailed == 0) {
            System.out.println("  The ticket lifecycle is working against the live database.");
            AppLogger.info(TicketVerification.class,
                    "Ticket verification passed " + checksRun + " checks");
        } else {
            System.out.println("  " + checksFailed + " check(s) failed. See the detail above.");
            AppLogger.warn(TicketVerification.class,
                    "Ticket verification had " + checksFailed + " failure(s)");
        }
        System.out.println();
        return checksFailed == 0 ? 0 : 1;
    }

    /* ---------- 1. Collecting problems rather than throwing at the first ---------- */

    private static void verifyValidationAccumulator() {
        section("1. Validation accumulator");

        ValidationResult empty = ValidationResult.forOperation("Nothing wrong");
        check("a clean result is valid", "true", String.valueOf(empty.isValid()));
        check("  and throws nothing", "(nothing thrown)", nameOfThrown(empty::throwIfInvalid));

        ValidationResult three = ValidationResult.forOperation("Three things wrong")
                .reject("first")
                .reject("second")
                .rejectIf(true, "third")
                .rejectIf(false, "not this one");
        check("problems accumulate", "3", String.valueOf(three.getProblems().size()));
        check("  blank rejections ignored", "3",
                String.valueOf(three.reject("   ").getProblems().size()));
        check("  the list is unmodifiable", "UnsupportedOperationException",
                nameOfThrown(() -> three.getProblems().clear()));
        check("  it throws once, not three times", "ValidationException",
                nameOfThrown(three::throwIfInvalid));

        // The point of the accumulator: a console can show every mistake in
        // one pass rather than one per attempt.
        int carried = 0;
        try {
            three.throwIfInvalid();
        } catch (ValidationException thrown) {
            carried = thrown.getFieldErrors().size();
        }
        check("  all three reach the caller", "3", String.valueOf(carried));
    }

    /* ---------- 2. Field rules ---------- */

    private static void verifyFieldValidators() {
        section("2. Field validators");

        check("blank is blank", "true", String.valueOf(Validators.isBlank("   ")));
        check("null trims to null", "null", String.valueOf(Validators.trimToNull(null)));
        check("whitespace trims to null", "null", String.valueOf(Validators.trimToNull("  ")));
        check("text trims", "hello", String.valueOf(Validators.trimToNull("  hello  ")));

        TicketRequest complete = new TicketRequest(1L, IncidentCategory.BROADBAND, DESCRIPTION);
        check("a complete request passes", "(nothing thrown)",
                nameOfThrown(() -> TicketValidator.validateRaise(complete)));

        check("no request at all", "ValidationException",
                nameOfThrown(() -> TicketValidator.validateRaise(null)));
        check("an empty request", "3", String.valueOf(problemsOf(
                () -> TicketValidator.validateRaise(new TicketRequest()))));
        check("a one word description", "1", String.valueOf(problemsOf(
                () -> TicketValidator.validateRaise(
                        new TicketRequest(1L, IncidentCategory.OTHER, "down")))));
        check("a description past the column width", "1", String.valueOf(problemsOf(
                () -> TicketValidator.validateRaise(new TicketRequest(1L,
                        IncidentCategory.OTHER, repeat("x", TicketValidator.DESCRIPTION_MAX + 1))))));
        check("a zero identifier", "1", String.valueOf(problemsOf(
                () -> TicketValidator.validateRaise(
                        new TicketRequest(0L, IncidentCategory.OTHER, DESCRIPTION)))));

        check("resolution needs all three parts", "3", String.valueOf(problemsOf(
                () -> TicketValidator.validateResolution(null, null, null))));
        check("a complete resolution passes", "(nothing thrown)",
                nameOfThrown(() -> TicketValidator.validateResolution(
                        ResolutionCode.FIBER_CUT, "Fibre cut on the access route",
                        "Spliced and tested")));

        check("remarks are optional", "(nothing thrown)",
                nameOfThrown(() -> TicketValidator.validateRemarks(null)));
        check("  but must fit the column", "ValidationException",
                nameOfThrown(() -> TicketValidator.validateRemarks(
                        repeat("y", TicketValidator.REMARKS_MAX + 1))));
        check("a reason is required", "ValidationException",
                nameOfThrown(() -> TicketValidator.validateReason("op", "Reason", "  ")));

        check("rating 0 refused", "ValidationException",
                nameOfThrown(() -> TicketValidator.validateFeedback(0, null)));
        check("rating 6 refused", "ValidationException",
                nameOfThrown(() -> TicketValidator.validateFeedback(6, null)));
        check("rating 1 accepted", "(nothing thrown)",
                nameOfThrown(() -> TicketValidator.validateFeedback(1, null)));
        check("rating 5 accepted", "(nothing thrown)",
                nameOfThrown(() -> TicketValidator.validateFeedback(5, "Very good")));
    }

    /* ---------- 3. The transition graph ---------- */

    private static void verifyTransitionGraph() {
        section("3. Transition graph");

        check("open may be assigned", "true", String.valueOf(
                TicketLifecycle.canMove(TicketStatus.OPEN, TicketStatus.ASSIGNED)));
        check("open may be cancelled", "true", String.valueOf(
                TicketLifecycle.canMove(TicketStatus.OPEN, TicketStatus.CANCELLED)));
        check("open may not jump to in progress", "false", String.valueOf(
                TicketLifecycle.canMove(TicketStatus.OPEN, TicketStatus.IN_PROGRESS)));
        check("open may not be resolved", "false", String.valueOf(
                TicketLifecycle.canMove(TicketStatus.OPEN, TicketStatus.RESOLVED)));

        check("in progress may be resolved", "true", String.valueOf(
                TicketLifecycle.canMove(TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED)));
        check("pending customer may resume", "true", String.valueOf(
                TicketLifecycle.canMove(TicketStatus.PENDING_CUSTOMER,
                        TicketStatus.IN_PROGRESS)));
        check("escalated does not go back to assigned", "false", String.valueOf(
                TicketLifecycle.canMove(TicketStatus.ESCALATED, TicketStatus.ASSIGNED)));

        check("resolved may be closed", "true", String.valueOf(
                TicketLifecycle.canMove(TicketStatus.RESOLVED, TicketStatus.CLOSED)));
        check("resolved may be reopened", "true", String.valueOf(
                TicketLifecycle.canMove(TicketStatus.RESOLVED, TicketStatus.IN_PROGRESS)));
        check("  and that counts as a reopen", "true", String.valueOf(
                TicketLifecycle.isReopen(TicketStatus.RESOLVED, TicketStatus.IN_PROGRESS)));
        check("resolved may not be cancelled", "false", String.valueOf(
                TicketLifecycle.canMove(TicketStatus.RESOLVED, TicketStatus.CANCELLED)));

        check("closed is final", "0", String.valueOf(
                TicketLifecycle.nextStatesFrom(TicketStatus.CLOSED).size()));
        check("cancelled is final", "0", String.valueOf(
                TicketLifecycle.nextStatesFrom(TicketStatus.CANCELLED).size()));

        check("no state moves to itself", "true", String.valueOf(noSelfTransitions()));
        check("every state is reachable", "true", String.valueOf(everyStateReachable()));
        check("the graph is unmodifiable", "UnsupportedOperationException", nameOfThrown(
                () -> TicketLifecycle.nextStatesFrom(TicketStatus.OPEN).clear()));
        check("nulls are refused, not assumed", "0", String.valueOf(
                TicketLifecycle.nextStatesFrom(null).size()));

        check("an illegal move is explained", "BusinessException", nameOfThrown(
                () -> TicketLifecycle.requireTransition("TT-0000-000001",
                        TicketStatus.OPEN, TicketStatus.CLOSED)));
        check("  and the message names the alternatives", "true", String.valueOf(
                refusalFor(TicketStatus.OPEN, TicketStatus.CLOSED).contains("Assigned")));
        check("  a final state says so", "true", String.valueOf(
                refusalFor(TicketStatus.CLOSED, TicketStatus.OPEN).contains("final")));
        check("  and already-there says that", "true", String.valueOf(
                refusalFor(TicketStatus.OPEN, TicketStatus.OPEN).contains("already")));
    }

    private static boolean noSelfTransitions() {
        for (TicketStatus status : TicketStatus.values()) {
            if (TicketLifecycle.nextStatesFrom(status).contains(status)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Every state other than the starting one has to be reachable, or the
     * enum declares a state the system can never produce.
     */
    private static boolean everyStateReachable() {
        for (TicketStatus target : TicketStatus.values()) {
            if (target == TicketStatus.OPEN) {
                continue;
            }
            boolean reachable = false;
            for (TicketStatus from : TicketStatus.values()) {
                if (TicketLifecycle.canMove(from, target)) {
                    reachable = true;
                    break;
                }
            }
            if (!reachable) {
                return false;
            }
        }
        return true;
    }

    private static String refusalFor(TicketStatus from, TicketStatus to) {
        try {
            TicketLifecycle.requireTransition("TT-0000-000001", from, to);
            return "(nothing thrown)";
        } catch (RuntimeException thrown) {
            return String.valueOf(thrown.getMessage());
        }
    }

    /* ---------- 4. Ticket numbering ---------- */

    private static void verifyNumbering(DAOFactory factory) {
        section("4. Ticket numbering");

        TroubleTicketDAO tickets = factory.getTroubleTicketDAO();
        TicketNumberGenerator generator = new TicketNumberGenerator(tickets);

        int year = LocalDate.now().getYear();
        int highest = tickets.findHighestSequenceForYear(year);

        String first = generator.nextFor(year);
        check("shape matches the case study", "true",
                String.valueOf(first.matches("TT-\\d{4}-\\d{6}")));
        check("continues from the table", String.format("TT-%d-%06d", year, highest + 1), first);
        check("the next one differs", String.format("TT-%d-%06d", year, highest + 2),
                generator.nextFor(year));
        check("a different year has its own run", "TT-1999-000001",
                generator.nextFor(1999));
        check("  and keeps counting", "TT-1999-000002", generator.nextFor(1999));

        generator.resync(1999);
        check("resync rereads the table", "TT-1999-000001", generator.nextFor(1999));

        check("no issued number collides with a stored one", "true", String.valueOf(
                !tickets.findByTicketNumber(first).isPresent()));
        check("the default year is this one", "true",
                String.valueOf(generator.next().startsWith("TT-" + year + "-")));
        check("a DAO is required", "IllegalArgumentException",
                nameOfThrown(() -> new TicketNumberGenerator(null)));
    }

    /* ---------- 5. A ticket's whole life ---------- */

    private static void verifyLifecycle(DAOFactory factory, TicketService service) {
        TroubleTicketDAO tickets = factory.getTroubleTicketDAO();

        TroubleTicket template = tickets.findByTicketNumber(TEMPLATE_TICKET)
                .orElseThrow(() -> new IllegalStateException(
                        "Seed ticket " + TEMPLATE_TICKET + " is missing"));
        TroubleTicket otherCustomers = tickets.findByTicketNumber(OTHER_CUSTOMER_TICKET)
                .orElseThrow(() -> new IllegalStateException(
                        "Seed ticket " + OTHER_CUSTOMER_TICKET + " is missing"));
        Long engineerId = tickets.findByTicketNumber(ASSIGNED_TICKET)
                .map(TroubleTicket::getAssignedEngineerId)
                .orElseThrow(() -> new IllegalStateException(
                        "Seed ticket " + ASSIGNED_TICKET + " has no engineer"));

        UserSession customer = sessionFor(Role.CUSTOMER, "vfy-customer",
                template.getCustomerId(), null);
        UserSession stranger = sessionFor(Role.CUSTOMER, "vfy-stranger",
                otherCustomers.getCustomerId(), null);
        UserSession engineer = sessionFor(Role.NETWORK_ENGINEER, "vfy-engineer",
                null, engineerId);
        UserSession serviceDesk = sessionFor(Role.SERVICE_DESK, "vfy-sdesk", null, null);
        UserSession manager = sessionFor(Role.NETWORK_MANAGER, "vfy-manager", null, null);

        TransactionTemplate.run(context -> {
            Savepoint marker = context.savepoint("ticket_verification");
            try {
                verifyRaising(factory, service, customer, serviceDesk, stranger, template);
                verifyWorking(factory, service, customer, engineer, serviceDesk, engineerId,
                        template);
                verifyClosingPaths(factory, service, customer, engineer, serviceDesk, manager,
                        engineerId, template);
            } finally {
                context.rollbackTo(marker);
            }
        });

        check("nothing was left in the ticket table", "0",
                String.valueOf(countIssuedTickets(tickets)));
    }

    /* ---------- 6. The database's own version ---------- */

    /**
     * Exercises {@code sp_resolve_ticket}, the resolution written as a
     * stored procedure.
     *
     * <p>Outside any transaction on purpose. The procedure runs its own
     * START TRANSACTION and COMMIT, which in MySQL would commit whatever
     * the caller had open, so a savepoint here would protect nothing. The
     * probe ticket it works on is registered with {@link #ISSUED} and
     * deleted when the run finishes.</p>
     */
    private static void verifyStoredProcedure(DAOFactory factory, TicketService service) {
        section("9. The same resolution inside the database");

        TroubleTicketDAO tickets = factory.getTroubleTicketDAO();
        TroubleTicket template = tickets.findByTicketNumber(TEMPLATE_TICKET)
                .orElseThrow(() -> new IllegalStateException(
                        "Seed ticket " + TEMPLATE_TICKET + " is missing"));
        UserSession customer = sessionFor(Role.CUSTOMER, "vfy-customer",
                template.getCustomerId(), null);

        // The refusal paths return before the procedure opens a transaction,
        // so these two write nothing at all.
        ReportDAO.ProcedureOutcome missing = factory.getReportDAO()
                .resolveViaProcedure(999999999L, "none", "none", "UNKNOWN", "vfy-sdesk");
        check("the procedure refuses a ticket that does not exist", "false",
                String.valueOf(missing.isSuccess()));
        check("  and says so", "Ticket does not exist", missing.getMessage());

        TroubleTicket settled = tickets.findByTicketNumber(CLOSED_TICKET)
                .orElseThrow(() -> new IllegalStateException(
                        "Seed ticket " + CLOSED_TICKET + " is missing"));
        ReportDAO.ProcedureOutcome alreadyDone = factory.getReportDAO()
                .resolveViaProcedure(settled.getId(), "none", "none", "UNKNOWN", "vfy-sdesk");
        check("the procedure refuses a ticket that is already finished", "false",
                String.valueOf(alreadyDone.isSuccess()));
        check("  naming the state it is in", "true",
                String.valueOf(alreadyDone.getMessage().contains("CLOSED")));

        List<TelecomService> offered =
                service.ticketableServicesFor(customer, template.getCustomerId());
        TroubleTicket probe = raise(service, customer,
                new TicketRequest(offered.get(0).getId(), IncidentCategory.BROADBAND, DESCRIPTION));
        check("a probe ticket is open", "OPEN", String.valueOf(probe.getStatus()));

        ReportDAO.ProcedureOutcome resolved = factory.getReportDAO().resolveViaProcedure(
                probe.getId(), "Fibre cut on the access ring", "Spliced and re-tested",
                "FIBER_CUT", "vfy-sdesk");
        check("the procedure resolves it", "true", String.valueOf(resolved.isSuccess()));
        check("  and names the ticket", "true",
                String.valueOf(resolved.getMessage().contains(probe.getTicketNumber())));

        TroubleTicket after = tickets.getById(probe.getId());
        check("the ticket is Resolved", "RESOLVED", String.valueOf(after.getStatus()));
        check("  with the resolution code", "FIBER_CUT", String.valueOf(after.getResolutionCode()));
        check("  the root cause", "Fibre cut on the access ring", after.getRootCause());
        check("  the resolution", "Spliced and re-tested", after.getResolution());
        check("  and a resolution time", "true",
                String.valueOf(after.getResolutionDate() != null));
        check("  a history row was written", "RESOLVED", String.valueOf(
                factory.getTicketStatusHistoryDAO().findByTicketId(after.getId()).stream()
                        .map(TicketStatusHistory::getNewStatus)
                        .reduce((first, last) -> last).orElse(null)));
        check("  and an audit row", "true", String.valueOf(
                factory.getAuditLogDAO().findByEntity("TROUBLE_TICKET", after.getTicketNumber())
                        .stream().anyMatch(row -> "TICKET_RESOLVED".equals(row.getAction()))));
    }

    /* ---------- 5a. Raising ---------- */

    private static void verifyRaising(DAOFactory factory, TicketService service,
                                      UserSession customer, UserSession serviceDesk,
                                      UserSession stranger, TroubleTicket template) {
        section("5. Raising a ticket");

        List<TelecomService> offered =
                service.ticketableServicesFor(customer, template.getCustomerId());
        check("the customer has a service to complain about", "true",
                String.valueOf(!offered.isEmpty()));
        check("  and every one offered is ticketable", "true",
                String.valueOf(offered.stream().allMatch(TelecomService::isTicketable)));
        check("another customer's services are refused", "AuthorizationException",
                nameOfThrown(() -> service.ticketableServicesFor(stranger,
                        template.getCustomerId())));

        Long serviceId = offered.get(0).getId();

        TroubleTicket raised = raise(service, customer,
                new TicketRequest(serviceId, IncidentCategory.BROADBAND, DESCRIPTION));
        check("the ticket was stored", "true", String.valueOf(raised.isPersisted()));
        check("  numbered for this year", "true", String.valueOf(
                raised.getTicketNumber().startsWith("TT-" + LocalDate.now().getYear() + "-")));
        check("  it starts open", "OPEN", String.valueOf(raised.getStatus()));
        check("  the customer came from the service", String.valueOf(template.getCustomerId()),
                String.valueOf(raised.getCustomerId()));
        check("  raised by the signed in user", "vfy-customer", raised.getCreatedBy());
        check("  not auto created", "false", String.valueOf(raised.isAutoCreated()));
        check("  raise time has no sub-second part", "0",
                String.valueOf(raised.getCreatedDate().getNano()));

        check("  a resolution deadline was stamped", "true",
                String.valueOf(raised.getSlaDeadline() != null));
        check("  and a response deadline", "true",
                String.valueOf(raised.getSlaResponseDeadline() != null));
        check("  the deadlines survived the insert", String.valueOf(raised.getSlaDeadline()),
                String.valueOf(factory.getTroubleTicketDAO()
                        .getById(raised.getId()).getSlaDeadline()));

        // Broadband is not service affecting, so the derivation should land
        // on the same pair the seed shows for a customer raised call drop.
        check("  severity derived from the category", "MINOR",
                String.valueOf(raised.getSeverity()));
        check("  priority derived from the severity", "MEDIUM",
                String.valueOf(raised.getPriority()));
        check("  MEDIUM window is 12 hours", "true", String.valueOf(
                raised.getSlaDeadline().equals(raised.getCreatedDate().plusMinutes(720))));

        TroubleTicket outage = raise(service, customer,
                new TicketRequest(serviceId, IncidentCategory.NETWORK_OUTAGE, DESCRIPTION));
        check("a service affecting fault arrives critical", "CRITICAL",
                String.valueOf(outage.getSeverity()));
        check("  at critical priority", "CRITICAL", String.valueOf(outage.getPriority()));
        check("  which matches the sample ticket in the document", "true", String.valueOf(
                outage.getPriority() == Priority.CRITICAL
                        && outage.getSeverity() == Severity.CRITICAL));
        check("  and a 15 minute response window", "true", String.valueOf(
                outage.getSlaResponseDeadline()
                        .equals(outage.getCreatedDate().plusMinutes(15))));
        check("numbers are issued in sequence", "true", String.valueOf(
                sequenceOf(outage) == sequenceOf(raised) + 1));

        TroubleTicket judged = raise(service, serviceDesk, new TicketRequest(serviceId,
                IncidentCategory.BROADBAND, DESCRIPTION)
                .setPriority(Priority.HIGH)
                .setSeverity(Severity.MAJOR));
        check("the service desk may judge the priority", "HIGH",
                String.valueOf(judged.getPriority()));
        check("  and the severity", "MAJOR", String.valueOf(judged.getSeverity()));

        check("a customer choosing their own priority is refused", "ValidationException",
                nameOfThrown(() -> service.raise(customer, new TicketRequest(serviceId,
                        IncidentCategory.BROADBAND, DESCRIPTION)
                        .setPriority(Priority.CRITICAL))));
        check("another customer's service is refused", "AuthorizationException",
                nameOfThrown(() -> service.raise(stranger, new TicketRequest(serviceId,
                        IncidentCategory.BROADBAND, DESCRIPTION))));
        // A plausible key that happens not to exist, which is a lookup
        // failure. A negative one never reaches the lookup, because
        // validation refuses it as input first.
        check("a service that does not exist is refused", "ResourceNotFoundException",
                nameOfThrown(() -> service.raise(serviceDesk, new TicketRequest(999999L,
                        IncidentCategory.BROADBAND, DESCRIPTION))));
        check("  a negative key never gets that far", "ValidationException",
                nameOfThrown(() -> service.raise(serviceDesk, new TicketRequest(-1L,
                        IncidentCategory.BROADBAND, DESCRIPTION))));

        check("the creation entry is in the trail", "1", String.valueOf(
                factory.getTicketStatusHistoryDAO().countByTicketId(raised.getId())));
        TicketStatusHistory creation = factory.getTicketStatusHistoryDAO()
                .findByTicketId(raised.getId()).get(0);
        check("  it records a creation, not a move", "true",
                String.valueOf(creation.isCreationEntry()));
        check("  landing on open", "OPEN", String.valueOf(creation.getNewStatus()));
        check("the audit trail recorded it", "true", String.valueOf(
                factory.getAuditLogDAO()
                        .findByEntity("TROUBLE_TICKET", raised.getTicketNumber())
                        .stream()
                        .anyMatch(entry -> "TICKET_RAISED".equals(entry.getAction()))));

        // Terminated last, because it takes the service away from the checks
        // above. The rollback puts it back.
        factory.getTelecomServiceDAO().updateStatus(serviceId, ServiceStatus.TERMINATED);
        check("a terminated service cannot be ticketed", "BusinessException",
                nameOfThrown(() -> service.raise(serviceDesk, new TicketRequest(serviceId,
                        IncidentCategory.BROADBAND, DESCRIPTION))));
        factory.getTelecomServiceDAO().updateStatus(serviceId, ServiceStatus.ACTIVE);
        check("  and can again once active", "(nothing thrown)",
                nameOfThrown(() -> raise(service, serviceDesk, new TicketRequest(serviceId,
                        IncidentCategory.BROADBAND, DESCRIPTION))));
    }

    /* ---------- 5b. Working ---------- */

    private static void verifyWorking(DAOFactory factory, TicketService service,
                                      UserSession customer, UserSession engineer,
                                      UserSession serviceDesk, Long engineerId,
                                      TroubleTicket template) {
        section("6. Working a ticket");

        TroubleTicketDAO tickets = factory.getTroubleTicketDAO();
        Long serviceId = service.ticketableServicesFor(customer, template.getCustomerId())
                .get(0).getId();

        TroubleTicket ticket = raise(service, customer,
                new TicketRequest(serviceId, IncidentCategory.SLOW_DATA, DESCRIPTION));
        String number = ticket.getTicketNumber();

        check("an open ticket cannot be worked yet", "BusinessException", nameOfThrown(
                () -> service.changeStatus(serviceDesk, number, TicketStatus.IN_PROGRESS,
                        "too early")));
        check("  the offered moves say why", "[ASSIGNED, CANCELLED]",
                String.valueOf(service.nextStatesFor(serviceDesk, number)));

        check("assigned for the rest of these checks", "true",
                String.valueOf(assignTo(factory, ticket, engineerId)));

        check("no response recorded yet", "true", String.valueOf(
                tickets.getById(ticket.getId()).getFirstResponseDate() == null));

        service.changeStatus(engineer, number, TicketStatus.IN_PROGRESS, "Picked up");
        TroubleTicket working = tickets.getById(ticket.getId());
        check("the engineer started work", "IN_PROGRESS", String.valueOf(working.getStatus()));
        check("  which counts as the first response", "true",
                String.valueOf(working.getFirstResponseDate() != null));
        check("  the trail grew", "3",
                String.valueOf(factory.getTicketStatusHistoryDAO()
                        .countByTicketId(ticket.getId())));

        LocalDateTime firstResponse = working.getFirstResponseDate();
        service.changeStatus(engineer, number, TicketStatus.PENDING_CUSTOMER, "Awaiting logs");
        check("waiting on the customer", "PENDING_CUSTOMER", String.valueOf(
                tickets.getById(ticket.getId()).getStatus()));
        check("  the first response is not overwritten", String.valueOf(firstResponse),
                String.valueOf(tickets.getById(ticket.getId()).getFirstResponseDate()));
        check("  recording a response again says nothing changed", "false",
                String.valueOf(service.recordFirstResponse(engineer, number)));

        service.changeStatus(engineer, number, TicketStatus.IN_PROGRESS, "Logs received");
        check("back in progress", "IN_PROGRESS",
                String.valueOf(tickets.getById(ticket.getId()).getStatus()));

        check("resolving is not a bare status change", "BusinessException", nameOfThrown(
                () -> service.changeStatus(engineer, number, TicketStatus.RESOLVED, null)));
        check("  nor is assignment", "BusinessException", nameOfThrown(
                () -> service.changeStatus(serviceDesk, number, TicketStatus.ASSIGNED, null)));
        check("  nor escalation", "BusinessException", nameOfThrown(
                () -> service.changeStatus(serviceDesk, number, TicketStatus.ESCALATED, null)));
        check("  a null target is refused", "ValidationException", nameOfThrown(
                () -> service.changeStatus(engineer, number, null, null)));

        service.recordDiagnosis(engineer, number, "Oversubscribed OLT uplink at peak");
        check("the diagnosis was recorded", "Oversubscribed OLT uplink at peak",
                tickets.getById(ticket.getId()).getRootCause());
        check("  without resolving the ticket", "IN_PROGRESS",
                String.valueOf(tickets.getById(ticket.getId()).getStatus()));
        check("  a blank diagnosis is refused", "ValidationException", nameOfThrown(
                () -> service.recordDiagnosis(engineer, number, "  ")));

        check("a customer may not change status", "AuthorizationException", nameOfThrown(
                () -> service.changeStatus(customer, number, TicketStatus.IN_PROGRESS, null)));
        check("a service desk operator may not diagnose", "AuthorizationException",
                nameOfThrown(() -> service.recordDiagnosis(serviceDesk, number, "guesswork")));
        check("an unknown ticket is refused", "ResourceNotFoundException", nameOfThrown(
                () -> service.changeStatus(engineer, "TT-0000-000001",
                        TicketStatus.IN_PROGRESS, null)));
        check("a blank ticket number is refused", "ValidationException", nameOfThrown(
                () -> service.changeStatus(engineer, "  ", TicketStatus.IN_PROGRESS, null)));

        section("7. Priority changes move the deadline");

        LocalDateTime beforeChange = tickets.getById(ticket.getId()).getSlaDeadline();
        service.updatePriority(serviceDesk, number, Priority.CRITICAL, "Site is down");
        TroubleTicket urgent = tickets.getById(ticket.getId());
        check("the priority changed", "CRITICAL", String.valueOf(urgent.getPriority()));
        check("  the deadline moved in", "true",
                String.valueOf(urgent.getSlaDeadline().isBefore(beforeChange)));
        check("  to the two hour critical window", "true", String.valueOf(
                urgent.getSlaDeadline().equals(urgent.getCreatedDate().plusMinutes(120))));
        check("  and the response window with it", "true", String.valueOf(
                urgent.getSlaResponseDeadline()
                        .equals(urgent.getCreatedDate().plusMinutes(15))));
        check("  the change was audited", "true", String.valueOf(
                factory.getAuditLogDAO()
                        .findByEntity("TROUBLE_TICKET", number)
                        .stream()
                        .anyMatch(entry -> "PRIORITY_CHANGED".equals(entry.getAction()))));
        check("  setting the same priority again is refused", "BusinessException",
                nameOfThrown(() -> service.updatePriority(serviceDesk, number,
                        Priority.CRITICAL, "no change")));
        check("  an engineer may not set their own deadline", "AuthorizationException",
                nameOfThrown(() -> service.updatePriority(engineer, number, Priority.LOW,
                        "easier for me")));
        check("  a reason is required", "ValidationException", nameOfThrown(
                () -> service.updatePriority(serviceDesk, number, Priority.LOW, null)));
    }

    /* ---------- 5c. Resolving, rating, reopening, closing ---------- */

    private static void verifyClosingPaths(DAOFactory factory, TicketService service,
                                           UserSession customer, UserSession engineer,
                                           UserSession serviceDesk, UserSession manager,
                                           Long engineerId, TroubleTicket template) {
        section("8. Resolving and rating");

        TroubleTicketDAO tickets = factory.getTroubleTicketDAO();
        Long serviceId = service.ticketableServicesFor(customer, template.getCustomerId())
                .get(0).getId();

        TroubleTicket ticket = raise(service, customer,
                new TicketRequest(serviceId, IncidentCategory.BROADBAND, DESCRIPTION));
        String number = ticket.getTicketNumber();
        assignTo(factory, ticket, engineerId);
        service.changeStatus(engineer, number, TicketStatus.IN_PROGRESS, "Picked up");

        check("feedback before resolution is refused", "BusinessException", nameOfThrown(
                () -> service.submitFeedback(customer, number, 5, "too soon")));

        service.resolve(engineer, number, ResolutionCode.CONFIGURATION_ERROR,
                "Profile mismatch after a change", "Restored the previous profile");
        TroubleTicket resolved = tickets.getById(ticket.getId());
        check("the ticket is resolved", "RESOLVED", String.valueOf(resolved.getStatus()));
        check("  with a resolution date", "true",
                String.valueOf(resolved.getResolutionDate() != null));
        check("  a code", "CONFIGURATION_ERROR", String.valueOf(resolved.getResolutionCode()));
        check("  a root cause", "Profile mismatch after a change", resolved.getRootCause());
        check("  and the fix", "Restored the previous profile", resolved.getResolution());
        check("  the SLA verdict was settled", "WITHIN_SLA",
                String.valueOf(resolved.getSlaStatus()));
        check("  resolving twice is refused", "BusinessException", nameOfThrown(
                () -> service.resolve(engineer, number, ResolutionCode.UNKNOWN, "again",
                        "again")));
        check("  an incomplete resolution is refused", "3", String.valueOf(problemsOf(
                () -> service.resolve(engineer, number, null, null, null))));

        TicketDetailDTO card = service.track(customer, number);
        check("the customer can track it", number, card.getTicketNumber());
        check("  the card shows the resolution", "true",
                String.valueOf(card.toDetailBlock().contains("CONFIGURATION_ERROR")));
        check("  and how long it took", "true",
                String.valueOf(card.getResolutionHours() != null));

        Feedback rating = service.submitFeedback(customer, number, 4, "Sorted quickly");
        check("feedback was accepted", "4", String.valueOf(rating.getRating()));
        check("  and reads back", "4", String.valueOf(service.feedbackFor(customer, number)
                .map(Feedback::getRating).orElse(-1)));
        check("  rating twice is refused", "BusinessException", nameOfThrown(
                () -> service.submitFeedback(customer, number, 1, "changed my mind")));
        check("  an engineer may not rate their own work", "AuthorizationException",
                nameOfThrown(() -> service.submitFeedback(engineer, number, 5, "I did well")));
        check("  an out of range rating is refused", "ValidationException", nameOfThrown(
                () -> service.submitFeedback(customer, number, 9, null)));

        section("9. Reopening");

        service.reopen(serviceDesk, number, "Fault returned within the hour");
        TroubleTicket reopened = tickets.getById(ticket.getId());
        check("the ticket is back in progress", "IN_PROGRESS",
                String.valueOf(reopened.getStatus()));
        check("  the resolution date was cleared", "true",
                String.valueOf(reopened.getResolutionDate() == null));
        check("  the resolution code with it", "true",
                String.valueOf(reopened.getResolutionCode() == null));
        check("  and the resolution text", "true",
                String.valueOf(reopened.getResolution() == null));
        check("  the root cause is kept as a starting point", "true",
                String.valueOf(reopened.getRootCause() != null));
        check("  the trail still says what was tried", "true", String.valueOf(
                latestRemark(factory, ticket.getId()).contains("CONFIGURATION_ERROR")));
        check("  a reason is required", "ValidationException", nameOfThrown(
                () -> service.reopen(serviceDesk, number, "   ")));
        check("  an in progress ticket cannot be reopened", "BusinessException", nameOfThrown(
                () -> service.reopen(serviceDesk, number, "again")));

        section("10. Closing and cancelling");

        check("an unresolved ticket cannot be closed", "BusinessException", nameOfThrown(
                () -> service.close(serviceDesk, number, "premature")));

        service.resolve(engineer, number, ResolutionCode.HARDWARE_FAILURE,
                "Failed line card", "Replaced the card");
        check("an engineer may not close", "AuthorizationException", nameOfThrown(
                () -> service.close(engineer, number, "done")));

        service.close(manager, number, "Customer confirmed service restored");
        TroubleTicket closed = tickets.getById(ticket.getId());
        check("the ticket is closed", "CLOSED", String.valueOf(closed.getStatus()));
        check("  with a closed date", "true", String.valueOf(closed.getClosedDate() != null));
        check("  closing twice is refused", "BusinessException", nameOfThrown(
                () -> service.close(manager, number, "again")));
        check("  a closed ticket cannot be cancelled", "BusinessException", nameOfThrown(
                () -> service.cancel(manager, number, "too late")));
        check("  nor can its priority change", "BusinessException", nameOfThrown(
                () -> service.updatePriority(manager, number, Priority.LOW, "too late")));
        check("  nor can a diagnosis be added", "BusinessException", nameOfThrown(
                () -> service.recordDiagnosis(engineer, number, "afterthought")));
        check("  and there is nowhere left to go", "0",
                String.valueOf(service.nextStatesFor(manager, number).size()));

        List<TicketStatusHistory> trail = service.historyFor(manager, number);
        check("the whole life is in the trail", "true", String.valueOf(trail.size() >= 6));
        check("  it starts with the creation", "true",
                String.valueOf(trail.get(0).isCreationEntry()));
        check("  and ends closed", "CLOSED",
                String.valueOf(trail.get(trail.size() - 1).getNewStatus()));
        check("  in order, oldest first", "true", String.valueOf(isChained(trail)));

        TroubleTicket doomed = raise(service, customer,
                new TicketRequest(serviceId, IncidentCategory.BILLING, DESCRIPTION));
        check("a customer may not cancel", "AuthorizationException", nameOfThrown(
                () -> service.cancel(customer, doomed.getTicketNumber(), "changed my mind")));

        service.cancel(serviceDesk, doomed.getTicketNumber(), "Raised against the wrong line");
        TroubleTicket cancelled = tickets.getById(doomed.getId());
        check("the ticket is cancelled", "CANCELLED", String.valueOf(cancelled.getStatus()));
        check("  a cancelled ticket is final", "0", String.valueOf(
                service.nextStatesFor(serviceDesk, doomed.getTicketNumber()).size()));
        check("  and its SLA standing is not a breach", "WITHIN_SLA",
                String.valueOf(cancelled.getSlaStatus()));

        section("11. Who may see what");

        check("the customer sees their own ticket", "true", String.valueOf(
                service.track(customer, number) != null));
        check("the service desk sees any ticket", "true", String.valueOf(
                service.track(serviceDesk, number) != null));
        check("the engineer sees the one assigned to them", "true", String.valueOf(
                service.track(engineer, number) != null));
        check("the customer's list is their own", "true", String.valueOf(
                service.listForCustomer(customer, template.getCustomerId()).size() >= 4));
        check("  another customer's list is refused", "AuthorizationException", nameOfThrown(
                () -> service.listForCustomer(customer, otherCustomerId(factory))));
        check("the engineer's list is their own work", "true", String.valueOf(
                service.listAssignedTo(engineer, engineerId).size() >= 1));
        check("  another engineer's is refused", "AuthorizationException", nameOfThrown(
                () -> service.listAssignedTo(engineer, engineerId + 1)));
        check("the open queue is staff only", "AuthorizationException", nameOfThrown(
                () -> service.listOpen(customer)));
        check("  and the manager may read it", "true",
                String.valueOf(!service.listOpen(manager).isEmpty()));
        check("a customer may not read the trail of another's ticket", "AuthorizationException",
                nameOfThrown(() -> service.historyFor(strangerFor(factory), number)));
    }

    /* ---------- Helpers ---------- */

    /**
     * Raises through the service and remembers the number, so a stray can be
     * swept up if a later check throws outside the transaction.
     */
    private static TroubleTicket raise(TicketService service, UserSession actor,
                                       TicketRequest request) {
        TroubleTicket raised = service.raise(actor, request);
        ISSUED.add(raised.getTicketNumber());
        return raised;
    }

    /**
     * Gives a ticket an engineer so the working states become reachable.
     *
     * <p>Assignment is the assignment service's operation and it writes its
     * own trail entry. Both halves are mirrored here rather than only the
     * row, because the ordering check further down tests that the trail
     * reads as a chain, and a missing entry would break the chain for a
     * reason that has nothing to do with what is being tested.</p>
     */
    private static boolean assignTo(DAOFactory factory, TroubleTicket ticket, Long engineerId) {
        boolean assigned = factory.getTroubleTicketDAO()
                .assignEngineer(ticket.getId(), engineerId, TicketStatus.OPEN);
        if (assigned) {
            factory.getTicketStatusHistoryDAO().insert(new TicketStatusHistory(ticket.getId(),
                    TicketStatus.OPEN, TicketStatus.ASSIGNED, "vfy-sdesk",
                    "Assigned for verification"));
        }
        return assigned;
    }

    private static int sequenceOf(TroubleTicket ticket) {
        String number = ticket.getTicketNumber();
        return Integer.parseInt(number.substring(number.lastIndexOf('-') + 1));
    }

    private static String latestRemark(DAOFactory factory, Long ticketId) {
        List<TicketStatusHistory> trail =
                factory.getTicketStatusHistoryDAO().findByTicketId(ticketId);
        if (trail.isEmpty()) {
            return "";
        }
        String remark = trail.get(trail.size() - 1).getRemarks();
        return remark == null ? "" : remark;
    }

    /**
     * Whether each entry starts where the previous one ended, which is what
     * makes the trail readable as a story rather than a set of rows.
     */
    private static boolean isChained(List<TicketStatusHistory> trail) {
        for (int index = 1; index < trail.size(); index++) {
            if (trail.get(index).getOldStatus() != trail.get(index - 1).getNewStatus()) {
                return false;
            }
        }
        return true;
    }

    private static Long otherCustomerId(DAOFactory factory) {
        return factory.getTroubleTicketDAO().findByTicketNumber(OTHER_CUSTOMER_TICKET)
                .map(TroubleTicket::getCustomerId)
                .orElse(-1L);
    }

    private static UserSession strangerFor(DAOFactory factory) {
        return sessionFor(Role.CUSTOMER, "vfy-stranger", otherCustomerId(factory), null);
    }

    private static UserSession sessionFor(Role role, String username, Long customerId,
                                          Long engineerId) {
        UserAccount account = new UserAccount(username, "Verification Actor",
                username + "@example.test", role);
        account.setId(-1L);
        return new UserSession(account, null, customerId, engineerId);
    }

    /**
     * How many field problems a validation raised, or -1 when it raised
     * something else entirely.
     */
    private static int problemsOf(Runnable work) {
        try {
            work.run();
            return 0;
        } catch (ValidationException thrown) {
            return thrown.getFieldErrors().size();
        } catch (RuntimeException thrown) {
            return -1;
        }
    }

    /** Java 8 has no {@code String.repeat}. */
    private static String repeat(String unit, int times) {
        StringBuilder builder = new StringBuilder(unit.length() * times);
        for (int index = 0; index < times; index++) {
            builder.append(unit);
        }
        return builder.toString();
    }

    private static int countIssuedTickets(TroubleTicketDAO tickets) {
        int found = 0;
        for (String number : ISSUED) {
            if (tickets.findByTicketNumber(number).isPresent()) {
                found++;
            }
        }
        return found;
    }

    /**
     * Last resort cleanup. The rollback should have dealt with every probe,
     * so anything found here means a check threw outside the transaction.
     */
    private static int removeIssuedTickets(TroubleTicketDAO tickets) {
        int removed = 0;
        for (String number : ISSUED) {
            Optional<TroubleTicket> stray = tickets.findByTicketNumber(number);
            if (stray.isPresent() && tickets.deleteById(stray.get().getId())) {
                removed++;
            }
        }
        return removed;
    }

    private static String nameOfThrown(Runnable work) {
        try {
            work.run();
            return "(nothing thrown)";
        } catch (RuntimeException thrown) {
            return thrown.getClass().getSimpleName();
        }
    }

    private static void section(String title) {
        System.out.println("  " + title);
    }

    private static void check(String label, String expected, String actual) {
        checksRun++;
        boolean passed = expected.equals(actual);
        if (!passed) {
            checksFailed++;
        }
        System.out.println(String.format("    [%s] %-46s expected=%-22s actual=%s",
                passed ? "PASS" : "FAIL", label, expected, actual));
    }
}
