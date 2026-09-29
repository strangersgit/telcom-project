package com.amdocs.telecom.util;

import com.amdocs.telecom.dao.AuditLogDAO;
import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.NetworkEngineerDAO;
import com.amdocs.telecom.dao.NotificationDAO;
import com.amdocs.telecom.dao.ReportDAO;
import com.amdocs.telecom.dao.TicketStatusHistoryDAO;
import com.amdocs.telecom.dao.TransactionTemplate;
import com.amdocs.telecom.dao.TroubleTicketDAO;
import com.amdocs.telecom.dto.EngineerRecommendationDTO;
import com.amdocs.telecom.dto.TicketRequest;
import com.amdocs.telecom.model.AuditLog;
import com.amdocs.telecom.model.NetworkEngineer;
import com.amdocs.telecom.model.Notification;
import com.amdocs.telecom.model.TelecomService;
import com.amdocs.telecom.model.TicketStatusHistory;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.UserAccount;
import com.amdocs.telecom.model.enums.EngineerAvailability;
import com.amdocs.telecom.model.enums.IncidentCategory;
import com.amdocs.telecom.model.enums.NotificationType;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.model.enums.ResolutionCode;
import com.amdocs.telecom.model.enums.Role;
import com.amdocs.telecom.model.enums.Specialization;
import com.amdocs.telecom.model.enums.TicketStatus;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.EngineerAssignmentService;
import com.amdocs.telecom.service.TicketService;
import com.amdocs.telecom.service.assignment.AssignmentResult;
import com.amdocs.telecom.service.assignment.EngineerMatch;
import com.amdocs.telecom.service.assignment.EngineerRecommender;
import com.amdocs.telecom.service.assignment.MatchTier;
import com.amdocs.telecom.service.event.TicketEventPublisher;
import com.amdocs.telecom.service.impl.EngineerAssignmentServiceImpl;
import com.amdocs.telecom.service.impl.SlaServiceImpl;
import com.amdocs.telecom.service.impl.TicketServiceImpl;

import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Exercises the recommendation engine of section 7 and the assignment
 * transaction of section 19.
 *
 * <p>The ranking rules are checked first against a roster built in memory.
 * That is deliberate: the seeded team has exactly one engineer per skill, so
 * the live database cannot show what happens when two equally loaded
 * specialists compete, and the rules are the part most worth pinning down.
 * Everything after that runs against the real schema.</p>
 *
 * <p>Almost nothing is left behind. Every Java assignment happens inside one
 * transaction rolled back to a savepoint. The one exception is the stored
 * procedure, which opens and commits a transaction of its own and so cannot
 * be rolled back by its caller; it runs against {@link #PROCEDURE_TICKET}
 * and hands it back when it is done.</p>
 */
public final class AssignmentVerification {

    private AssignmentVerification() {
        throw new AssertionError("AssignmentVerification is not instantiable");
    }

    /** A seeded ticket with no engineer: Core Network, West. */
    private static final String UNASSIGNED_TICKET = "TT-2026-004521";

    /** A seeded ticket that already has an engineer. */
    private static final String ASSIGNED_TICKET = "TT-2026-004523";

    private static final String DESCRIPTION =
            "Assignment probe: the link drops for a few seconds every hour";

    /**
     * The seeded ticket section 9 assigns for real.
     *
     * <p>A seeded ticket rather than a probe, because the stored procedure
     * commits and a committed assignment cannot be taken back: the audit
     * trail is append only by design, and a probe deleted afterwards would
     * hand its number, and its trail, to the next ticket raised. This one
     * already exists and always will, so the rows it gathers stay attached
     * to the ticket they describe. It is put back to open and unassigned
     * when the section finishes.</p>
     */
    private static final String PROCEDURE_TICKET = "TT-2026-004522";

    /** The engineer holding the skill {@link #PROCEDURE_TICKET} calls for. */
    private static final String PROCEDURE_ENGINEER = "ENG1015";

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

        System.out.println("  Engineer assignment verification");
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println();

        try {
            verifyRankingRules();
            verifyWorkedExample();
            verifyRecommendingForRealTickets(factory);
            verifyAssignmentTransaction(factory);
            verifyStoredProcedure(factory);
        } catch (RuntimeException failure) {
            checksFailed++;
            System.out.println("  Verification aborted: " + failure);
            AppLogger.error(AssignmentVerification.class, "Assignment verification aborted",
                    failure);
        } finally {
            int strays = removeIssuedTickets(factory.getTroubleTicketDAO());
            if (strays > 0) {
                System.out.println();
                System.out.println("  Removed " + strays + " stray probe ticket(s)");
            }
            int repaired = factory.getNetworkEngineerDAO().recalculateWorkloads();
            System.out.println("  Recomputed the workload of " + repaired + " engineer(s)");
        }

        System.out.println();
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println("  " + (checksRun - checksFailed) + " of " + checksRun
                + " checks passed");
        System.out.println();

        if (checksFailed == 0) {
            System.out.println("  Recommendation and assignment are working against the live "
                    + "database.");
            AppLogger.info(AssignmentVerification.class,
                    "Assignment verification passed " + checksRun + " checks");
        } else {
            System.out.println("  " + checksFailed + " check(s) failed. See the detail above.");
            AppLogger.warn(AssignmentVerification.class,
                    "Assignment verification had " + checksFailed + " failure(s)");
        }
        System.out.println();
        return checksFailed == 0 ? 0 : 1;
    }

    /* ---------- 1. The ranking rules ---------- */

    private static void verifyRankingRules() {
        section("1. The ranking rules of section 7");

        EngineerRecommender recommender = new EngineerRecommender();

        // Two Core Network engineers in the West, so workload and experience
        // have something to decide between.
        NetworkEngineer local = engineer("E-LOCAL", Specialization.CORE_NETWORK, Region.WEST,
                9, 2, EngineerAvailability.AVAILABLE);
        NetworkEngineer localBusier = engineer("E-LOCAL2", Specialization.CORE_NETWORK,
                Region.WEST, 12, 5, EngineerAvailability.AVAILABLE);
        NetworkEngineer remote = engineer("E-REMOTE", Specialization.CORE_NETWORK, Region.SOUTH,
                4, 0, EngineerAvailability.AVAILABLE);
        NetworkEngineer wrongSkill = engineer("E-OTHER", Specialization.BROADBAND, Region.WEST,
                15, 0, EngineerAvailability.AVAILABLE);
        NetworkEngineer elsewhere = engineer("E-NEITHER", Specialization.RAN, Region.EAST,
                20, 0, EngineerAvailability.AVAILABLE);
        NetworkEngineer onLeave = engineer("E-LEAVE", Specialization.CORE_NETWORK, Region.WEST,
                18, 0, EngineerAvailability.ON_LEAVE);
        NetworkEngineer offShift = engineer("E-SHIFT", Specialization.CORE_NETWORK, Region.WEST,
                18, 0, EngineerAvailability.OFF_SHIFT);
        NetworkEngineer full = engineer("E-FULL", Specialization.CORE_NETWORK, Region.WEST,
                18, 8, EngineerAvailability.AVAILABLE);
        full.setMaxTicketCapacity(8);

        List<NetworkEngineer> roster = Arrays.asList(local, localBusier, remote, wrongSkill,
                elsewhere, onLeave, offShift, full);

        List<EngineerMatch> ranked = recommender.rank(roster, Specialization.CORE_NETWORK,
                Region.WEST);
        check("candidates are ranked, not filtered to one", "4",
                String.valueOf(ranked.size()));
        check("  closest fit first", "E-LOCAL, E-LOCAL2, E-REMOTE, E-OTHER", codesOf(ranked));
        check("  with the reason each was found",
                "[EXACT, EXACT, OUT_OF_REGION, REGION_ONLY]", tiersOf(ranked));

        check("the wrong skill in the wrong region is no candidate", "false",
                String.valueOf(codesOf(ranked).contains("E-NEITHER")));

        // Availability and workload are gates, not preferences: an engineer
        // who cannot take the ticket is absent, however well they match.
        check("an engineer on leave is excluded", "false",
                String.valueOf(codesOf(ranked).contains("E-LEAVE")));
        check("  as is one off shift", "false",
                String.valueOf(codesOf(ranked).contains("E-SHIFT")));
        check("  and one already at capacity", "false",
                String.valueOf(codesOf(ranked).contains("E-FULL")));
        check("  even though all three hold the right skill", "true",
                String.valueOf(onLeave.hasSpecialization(Specialization.CORE_NETWORK)
                        && offShift.hasSpecialization(Specialization.CORE_NETWORK)
                        && full.hasSpecialization(Specialization.CORE_NETWORK)));

        // Within a tier the lighter workload wins, and experience only
        // breaks a tie: E-LOCAL2 has three more years than E-LOCAL but three
        // more tickets, and comes second.
        check("workload outranks experience", "E-LOCAL", codesOf(ranked).split(", ")[0]);

        NetworkEngineer sameLoadLessExperience = engineer("E-JUNIOR",
                Specialization.CORE_NETWORK, Region.WEST, 3, 2, EngineerAvailability.AVAILABLE);
        List<EngineerMatch> tied = recommender.rank(
                Arrays.asList(sameLoadLessExperience, local), Specialization.CORE_NETWORK,
                Region.WEST);
        check("equal workload is broken by experience", "E-LOCAL, E-JUNIOR", codesOf(tied));

        NetworkEngineer twin = engineer("E-AAA", Specialization.CORE_NETWORK, Region.WEST,
                9, 2, EngineerAvailability.AVAILABLE);
        List<EngineerMatch> identical = recommender.rank(Arrays.asList(local, twin),
                Specialization.CORE_NETWORK, Region.WEST);
        check("  and identical engineers by code, so the order is stable",
                "E-AAA, E-LOCAL", codesOf(identical));

        // The last tier is offered and never taken: handing a core network
        // fault to a broadband engineer is a judgement nothing here can make.
        Optional<EngineerMatch> best = recommender.best(roster, Specialization.CORE_NETWORK,
                Region.WEST);
        check("the best candidate is the closest fit", "E-LOCAL",
                best.map(EngineerMatch::getEmployeeCode).orElse("(none)"));
        Optional<EngineerMatch> onlyOtherSkills = recommender.best(
                Arrays.asList(wrongSkill, elsewhere), Specialization.CORE_NETWORK, Region.WEST);
        check("a different skill is never chosen automatically", "(none)",
                onlyOtherSkills.map(EngineerMatch::getEmployeeCode).orElse("(none)"));
        check("  though it is still offered", "E-OTHER", codesOf(recommender.rank(
                Arrays.asList(wrongSkill, elsewhere), Specialization.CORE_NETWORK,
                Region.WEST)));
        check("nobody suitable is an empty answer, not an error", "false",
                String.valueOf(recommender.best(Collections.<NetworkEngineer>emptyList(),
                        Specialization.CORE_NETWORK, Region.WEST).isPresent()));

        // Without a region the first two tiers cannot be told apart, and the
        // third would be every engineer anywhere, which is no recommendation.
        List<EngineerMatch> regionless = recommender.rank(roster, Specialization.CORE_NETWORK,
                null);
        check("no region means every specialist is an exact match",
                "[EXACT, EXACT, EXACT]", tiersOf(regionless));
        check("  ranked on workload alone", "E-REMOTE, E-LOCAL, E-LOCAL2",
                codesOf(regionless));

        check("an empty roster ranks nobody", "0", String.valueOf(recommender
                .rank(Collections.<NetworkEngineer>emptyList(), Specialization.RAN, Region.WEST)
                .size()));
        check("a null roster ranks nobody", "0",
                String.valueOf(recommender.rank(null, Specialization.RAN, Region.WEST).size()));
        check("ranking without a required skill is refused", "IllegalArgumentException",
                nameOfThrown(() -> recommender.rank(roster, null, Region.WEST)));

        check("the shortlist defaults to three", "3", String.valueOf(recommender
                .shortlist(roster, Specialization.CORE_NETWORK, Region.WEST, 0).size()));
        check("  a request for one gives one", "1", String.valueOf(recommender
                .shortlist(roster, Specialization.CORE_NETWORK, Region.WEST, 1).size()));
        check("  and a huge request is capped by the candidates", "4", String.valueOf(
                recommender.shortlist(roster, Specialization.CORE_NETWORK, Region.WEST, 10000)
                        .size()));

        check("the tiers are declared best first", "[EXACT, OUT_OF_REGION, REGION_ONLY]",
                Arrays.toString(MatchTier.values()));
        check("  and only the last is off limits to the engine", "true",
                String.valueOf(MatchTier.EXACT.isAutomatic()
                        && MatchTier.OUT_OF_REGION.isAutomatic()
                        && !MatchTier.REGION_ONLY.isAutomatic()));

        EngineerRecommendationDTO view = ranked.get(0).toRecommendation();
        check("a candidate renders as the console's shape", "E-LOCAL", view.getEmployeeCode());
        check("  carrying the workload", "2 of 10",
                view.getActiveTicketCount() + " of " + view.getMaxTicketCapacity());
        check("  and the spare capacity", "8", String.valueOf(view.getSpareCapacity()));
    }

    /* ---------- 2. Section 16's worked example ---------- */

    private static void verifyWorkedExample() {
        section("2. Section 16's worked example");

        EngineerRecommender recommender = new EngineerRecommender();
        List<NetworkEngineer> roster = Arrays.asList(
                engineer("E-4", Specialization.RAN, Region.WEST, 5, 4,
                        EngineerAvailability.AVAILABLE),
                engineer("E-1", Specialization.RAN, Region.NORTH, 5, 1,
                        EngineerAvailability.AVAILABLE),
                engineer("E-0", Specialization.RAN, Region.EAST, 5, 0,
                        EngineerAvailability.AVAILABLE),
                engineer("E-2", Specialization.RAN, Region.SOUTH, 5, 2,
                        EngineerAvailability.AVAILABLE),
                engineer("E-BUSY", Specialization.RAN, Region.WEST, 5, 0,
                        EngineerAvailability.BUSY),
                engineer("E-WRONG", Specialization.BROADBAND, Region.WEST, 5, 0,
                        EngineerAvailability.AVAILABLE));

        // "Find the three engineers with the lowest active workload who have
        // the required specialization and are currently available."
        List<NetworkEngineer> three = recommender.lowestWorkload(roster, Specialization.RAN, 3);
        check("three engineers are returned", "3", String.valueOf(three.size()));
        check("  the least busy first", "E-0, E-1, E-2", namesOfEngineers(three));
        check("  all holding the required skill", "true", String.valueOf(three.stream()
                .allMatch(one -> one.hasSpecialization(Specialization.RAN))));
        check("  and all able to take work", "true",
                String.valueOf(three.stream().allMatch(NetworkEngineer::canAcceptWork)));

        // The example says nothing about region, so neither does the method.
        check("region plays no part in the example", "true", String.valueOf(
                three.stream().map(NetworkEngineer::getRegion).distinct().count() == 3));
        check("an unavailable engineer is left out", "false",
                String.valueOf(namesOfEngineers(three).contains("E-BUSY")));
        check("the count defaults to three", "3", String.valueOf(EngineerRecommender
                .DEFAULT_SHORTLIST));
        check("  and is used when none is given", "3", String.valueOf(
                recommender.lowestWorkload(roster, Specialization.RAN, 0).size()));
    }

    /* ---------- 3. Recommending for real tickets ---------- */

    private static void verifyRecommendingForRealTickets(DAOFactory factory) {
        section("3. Recommending for a seeded ticket");

        EngineerAssignmentService service = new EngineerAssignmentServiceImpl(factory,
                TicketEventPublisher.getInstance());
        UserSession serviceDesk = sessionFor(Role.SERVICE_DESK, "vfy-sdesk", null, null);
        UserSession manager = sessionFor(Role.NETWORK_MANAGER, "vfy-manager", null, null);
        UserSession customer = sessionFor(Role.CUSTOMER, "vfy-customer", 1L, null);
        UserSession engineerSession = sessionFor(Role.NETWORK_ENGINEER, "vfy-engineer", null, 1L);

        // A Network Outage for a West customer needs Core Network, which
        // ENG1008 holds in the West.
        List<EngineerMatch> candidates = service.recommend(serviceDesk, UNASSIGNED_TICKET, 5);
        check("the seeded ticket has candidates", "true",
                String.valueOf(!candidates.isEmpty()));
        check("  the best is the local specialist", "ENG1008",
                candidates.get(0).getEmployeeCode());
        check("  found as an exact match", "EXACT",
                String.valueOf(candidates.get(0).getTier()));
        check("  and the engine would choose them", "ENG1008",
                service.recommendBest(serviceDesk, UNASSIGNED_TICKET)
                        .map(EngineerMatch::getEmployeeCode).orElse("(none)"));

        // The database answers the same question through
        // sp_recommend_engineers, which only knows about exact matches.
        List<EngineerRecommendationDTO> viaProcedure =
                service.recommendViaProcedure(serviceDesk, UNASSIGNED_TICKET, 5);
        check("the procedure agrees on the exact matches",
                codesOfExact(candidates), codesOfDto(viaProcedure));

        check("the manager may also recommend", "true", String.valueOf(
                !service.recommend(manager, UNASSIGNED_TICKET, 3).isEmpty()));
        check("a customer may not", "AuthorizationException",
                nameOfThrown(() -> service.recommend(customer, UNASSIGNED_TICKET, 3)));
        check("  nor an engineer", "AuthorizationException",
                nameOfThrown(() -> service.recommend(engineerSession, UNASSIGNED_TICKET, 3)));
        check("an unknown ticket", "ResourceNotFoundException",
                nameOfThrown(() -> service.recommend(serviceDesk, "TT-1999-000001", 3)));
        check("  a blank number is refused as input", "ValidationException",
                nameOfThrown(() -> service.recommend(serviceDesk, "  ", 3)));

        check("the queue is the service desk's work list", "true", String.valueOf(
                service.unassignedQueue(serviceDesk).stream()
                        .allMatch(one -> one.getAssignedEngineerId() == null)));
        check("  most urgent first", "true",
                String.valueOf(isPriorityOrdered(service.unassignedQueue(serviceDesk))));
    }

    /* ---------- 4. The section 19 transaction ---------- */

    private static void verifyAssignmentTransaction(DAOFactory factory) {
        TroubleTicketDAO tickets = factory.getTroubleTicketDAO();
        NetworkEngineerDAO engineers = factory.getNetworkEngineerDAO();

        TroubleTicket template = tickets.findByTicketNumber(UNASSIGNED_TICKET)
                .orElseThrow(() -> new IllegalStateException(
                        "Seed ticket " + UNASSIGNED_TICKET + " is missing"));

        EngineerAssignmentService service = new EngineerAssignmentServiceImpl(factory,
                TicketEventPublisher.getInstance());
        TicketService ticketService = new TicketServiceImpl(factory, new SlaServiceImpl(),
                TicketEventPublisher.getInstance());
        UserSession serviceDesk = sessionFor(Role.SERVICE_DESK, "vfy-sdesk", null, null);
        UserSession customer = sessionFor(Role.CUSTOMER, "vfy-customer",
                template.getCustomerId(), null);

        TransactionTemplate.run(context -> {
            Savepoint marker = context.savepoint("assignment_verification");
            try {
                verifyHandover(factory, service, ticketService, serviceDesk, customer, template);
                verifyRefusals(factory, service, ticketService, serviceDesk, customer, template);
                verifyReassignment(factory, service, ticketService, serviceDesk, customer,
                        template);
                verifyBusyFlag(engineers, service, ticketService, serviceDesk, customer,
                        template);
                verifySweep(factory, service, ticketService, serviceDesk, customer, template);
            } finally {
                context.rollbackTo(marker);
            }
        });

        check("nothing was left in the ticket table", "0",
                String.valueOf(countIssuedTickets(tickets)));
    }

    /* ---------- 4a. Handing a ticket over ---------- */

    private static void verifyHandover(DAOFactory factory, EngineerAssignmentService service,
                                       TicketService ticketService, UserSession serviceDesk,
                                       UserSession customer, TroubleTicket template) {
        section("4. Handing a ticket over, as section 19's nine steps");

        TroubleTicketDAO tickets = factory.getTroubleTicketDAO();
        NetworkEngineerDAO engineers = factory.getNetworkEngineerDAO();
        TicketStatusHistoryDAO history = factory.getTicketStatusHistoryDAO();
        AuditLogDAO auditLog = factory.getAuditLogDAO();
        NotificationDAO inbox = factory.getNotificationDAO();

        TroubleTicket probe = raise(ticketService, customer, template,
                IncidentCategory.NETWORK_OUTAGE);
        String number = probe.getTicketNumber();

        check("the probe starts with nobody on it", "true",
                String.valueOf(probe.getAssignedEngineerId() == null));
        long before = engineers.findByEmployeeCode("ENG1008")
                .map(NetworkEngineer::getActiveTicketCount).orElse(-1);

        AssignmentResult outcome = service.autoAssign(serviceDesk, number);
        check("the engine assigned it", "true", String.valueOf(outcome.isAssigned()));
        check("  to the local specialist", "ENG1008",
                outcome.findEmployeeCode().orElse("(none)"));
        check("  and says how it found them", "EXACT",
                outcome.findTier().map(Enum::name).orElse("(none)"));

        TroubleTicket assigned = tickets.getById(probe.getId());
        check("the ticket moved to Assigned", "ASSIGNED", String.valueOf(assigned.getStatus()));
        check("  naming the engineer", "true",
                String.valueOf(assigned.getAssignedEngineerId() != null));
        // Stamped by trg_tickets_before_update when an engineer first lands
        // on a ticket, so the Java path gets it without asking.
        check("  and stamped with when", "true",
                String.valueOf(assigned.getAssignedDate() != null));

        check("the engineer's workload rose by one", String.valueOf(before + 1),
                String.valueOf(engineers.findByEmployeeCode("ENG1008")
                        .map(NetworkEngineer::getActiveTicketCount).orElse(-1)));

        List<TicketStatusHistory> trail = history.findByTicketId(probe.getId());
        check("the trail records the move", "OPEN -> ASSIGNED",
                trail.get(trail.size() - 1).getOldStatus() + " -> "
                        + trail.get(trail.size() - 1).getNewStatus());
        check("  with the engineer and the reason", "true", String.valueOf(
                trail.get(trail.size() - 1).getRemarks().contains("ENG1008")
                        && trail.get(trail.size() - 1).getRemarks().contains("in region")));

        // Steps 7 and 8 are the observers' work, not the service's.
        check("an audit row was written", "ENGINEER_ASSIGNED",
                newestAudit(auditLog, assigned).getAction());
        check("  by the actor", serviceDesk.getUsername(),
                newestAudit(auditLog, assigned).getPerformedBy());
        check("the customer and the engineer were told",
                "[CUSTOMER, NETWORK_ENGINEER]", rolesTold(inbox, assigned));
        check("  and nobody else", "2", String.valueOf(
                notificationsOfType(inbox, assigned, NotificationType.ENGINEER_ASSIGNED).size()));
        check("  the message naming the engineer", "true",
                String.valueOf(notificationsOfType(inbox, assigned,
                        NotificationType.ENGINEER_ASSIGNED).stream()
                        .anyMatch(sent -> sent.getMessage().contains("ENG1008"))));

        // An out of region specialist beats a local non-specialist, so a
        // Broadband fault for a West customer goes to ENG1021 in the South.
        TroubleTicket remote = raise(ticketService, customer, template,
                IncidentCategory.BROADBAND);
        AssignmentResult widened = service.autoAssign(serviceDesk, remote.getTicketNumber());
        check("a fault with no local specialist widens the search", "ENG1021",
                widened.findEmployeeCode().orElse("(none)"));
        check("  and says so", "OUT_OF_REGION", widened.findTier().map(Enum::name).orElse("-"));

        AssignmentResult named = service.assign(serviceDesk,
                raise(ticketService, customer, template, IncidentCategory.SLOW_DATA)
                        .getTicketNumber(), "ENG1030");
        check("a named engineer is taken as given", "ENG1030",
                named.findEmployeeCode().orElse("(none)"));
        check("  with no tier, because nobody searched", "(none)",
                named.findTier().map(Enum::name).orElse("(none)"));
    }

    /* ---------- 4b. Refusals ---------- */

    private static void verifyRefusals(DAOFactory factory, EngineerAssignmentService service,
                                       TicketService ticketService, UserSession serviceDesk,
                                       UserSession customer, TroubleTicket template) {
        section("5. What assignment refuses");

        TroubleTicket probe = raise(ticketService, customer, template,
                IncidentCategory.NETWORK_OUTAGE);
        String number = probe.getTicketNumber();

        check("an unknown engineer", "ResourceNotFoundException",
                nameOfThrown(() -> service.assign(serviceDesk, number, "ENG9999")));
        check("  a blank code is refused as input", "ValidationException",
                nameOfThrown(() -> service.assign(serviceDesk, number, "   ")));
        // ENG1042 is seeded on leave so the assignment engine has somebody
        // to refuse.
        check("an engineer on leave", "BusinessException",
                nameOfThrown(() -> service.assign(serviceDesk, number, "ENG1042")));
        check("  and the refusal says why", "true", String.valueOf(
                messageOfThrown(() -> service.assign(serviceDesk, number, "ENG1042"))
                        .contains("on leave")));
        check("a customer may not assign", "AuthorizationException",
                nameOfThrown(() -> service.assign(customer, number, "ENG1008")));

        service.assign(serviceDesk, number, "ENG1008");
        check("assigning an assigned ticket", "BusinessException",
                nameOfThrown(() -> service.assign(serviceDesk, number, "ENG1030")));
        check("  and points at reassignment", "true", String.valueOf(
                messageOfThrown(() -> service.assign(serviceDesk, number, "ENG1030"))
                        .contains("reassignment")));

        // A finished ticket keeps the engineer who worked it.
        UserSession engineer = sessionFor(Role.NETWORK_ENGINEER, "vfy-eng",
                null, factory.getTroubleTicketDAO().getById(probe.getId())
                        .getAssignedEngineerId());
        ticketService.changeStatus(engineer, number, TicketStatus.IN_PROGRESS, "picked up");
        ticketService.resolve(engineer, number, ResolutionCode.CONFIGURATION_ERROR,
                "Profile mismatch", "Restored the profile");
        check("reassigning a resolved ticket", "BusinessException", nameOfThrown(
                () -> service.reassign(serviceDesk, number, "ENG1030", "too late")));

        TroubleTicket fresh = raise(ticketService, customer, template,
                IncidentCategory.NETWORK_OUTAGE);
        check("reassigning a ticket nobody holds", "BusinessException", nameOfThrown(
                () -> service.reassign(serviceDesk, fresh.getTicketNumber(), "ENG1008",
                        "nobody has it")));
        check("  and points at assignment", "true", String.valueOf(messageOfThrown(
                () -> service.reassign(serviceDesk, fresh.getTicketNumber(), "ENG1008", "why"))
                .contains("assignment operation")));

        // Transmission is held only by ENG1042, who is on leave, and a local
        // engineer with a different skill is offered but never chosen.
        TroubleTicket unservable = raise(ticketService, customer, template,
                IncidentCategory.ENTERPRISE_LINK);
        check("nobody can be chosen automatically", "BusinessException", nameOfThrown(
                () -> service.autoAssign(serviceDesk, unservable.getTicketNumber())));
        check("  and the refusal names the skill needed", "true", String.valueOf(messageOfThrown(
                () -> service.autoAssign(serviceDesk, unservable.getTicketNumber()))
                .contains("Transmission")));
        check("  while candidates are still offered", "true", String.valueOf(
                !service.recommend(serviceDesk, unservable.getTicketNumber(), 3).isEmpty()));
        check("  all of them a different skill", "true", String.valueOf(
                service.recommend(serviceDesk, unservable.getTicketNumber(), 3).stream()
                        .noneMatch(EngineerMatch::isAutomatic)));
    }

    /* ---------- 4c. Reassignment ---------- */

    private static void verifyReassignment(DAOFactory factory,
                                           EngineerAssignmentService service,
                                           TicketService ticketService, UserSession serviceDesk,
                                           UserSession customer, TroubleTicket template) {
        section("6. Moving a ticket to another engineer");

        TroubleTicketDAO tickets = factory.getTroubleTicketDAO();
        NetworkEngineerDAO engineers = factory.getNetworkEngineerDAO();
        TicketStatusHistoryDAO history = factory.getTicketStatusHistoryDAO();
        NotificationDAO inbox = factory.getNotificationDAO();

        TroubleTicket probe = raise(ticketService, customer, template,
                IncidentCategory.NETWORK_OUTAGE);
        String number = probe.getTicketNumber();
        service.assign(serviceDesk, number, "ENG1008");

        // Moved while being worked, to show the status is left alone.
        UserSession engineer = sessionFor(Role.NETWORK_ENGINEER, "vfy-eng", null,
                tickets.getById(probe.getId()).getAssignedEngineerId());
        ticketService.changeStatus(engineer, number, TicketStatus.IN_PROGRESS, "picked up");

        int outgoingBefore = workloadOf(engineers, "ENG1008");
        int incomingBefore = workloadOf(engineers, "ENG1030");

        AssignmentResult moved = service.reassign(serviceDesk, number, "ENG1030",
                "ENG1008 pulled onto a major outage");
        check("the ticket moved", "true", String.valueOf(moved.isAssigned()));
        check("  from", "ENG1008", moved.findPreviousEmployeeCode().orElse("(none)"));
        check("  to", "ENG1030", moved.findEmployeeCode().orElse("(none)"));

        TroubleTicket after = tickets.getById(probe.getId());
        check("the new engineer holds it", "ENG1030",
                engineers.findById(after.getAssignedEngineerId())
                        .map(NetworkEngineer::getEmployeeCode).orElse("(none)"));
        // Reassignment changes who owns the ticket, not what state it is in.
        check("  and the status is untouched", "IN_PROGRESS", String.valueOf(after.getStatus()));

        check("the outgoing engineer gave up a slot", String.valueOf(outgoingBefore - 1),
                String.valueOf(workloadOf(engineers, "ENG1008")));
        check("the incoming engineer took one", String.valueOf(incomingBefore + 1),
                String.valueOf(workloadOf(engineers, "ENG1030")));

        List<TicketStatusHistory> trail = history.findByTicketId(probe.getId());
        TicketStatusHistory newest = trail.get(trail.size() - 1);
        check("the trail records the handover", "IN_PROGRESS -> IN_PROGRESS",
                newest.getOldStatus() + " -> " + newest.getNewStatus());
        check("  naming both engineers", "true", String.valueOf(
                newest.getRemarks().contains("ENG1008")
                        && newest.getRemarks().contains("ENG1030")));
        check("  and the reason", "true",
                String.valueOf(newest.getRemarks().contains("major outage")));
        check("the new engineer was told", "true", String.valueOf(
                inbox.findByTicketId(probe.getId()).stream()
                        .anyMatch(sent -> sent.getMessage().contains("ENG1030"))));

        check("moving it to the engineer who has it", "BusinessException", nameOfThrown(
                () -> service.reassign(serviceDesk, number, "ENG1030", "no change")));
        check("moving it without a reason", "ValidationException", nameOfThrown(
                () -> service.reassign(serviceDesk, number, "ENG1008", "   ")));
        check("moving it to somebody on leave", "BusinessException", nameOfThrown(
                () -> service.reassign(serviceDesk, number, "ENG1042", "they are on leave")));
    }

    /* ---------- 4d. The busy flag ---------- */

    private static void verifyBusyFlag(NetworkEngineerDAO engineers,
                                       EngineerAssignmentService service,
                                       TicketService ticketService, UserSession serviceDesk,
                                       UserSession customer, TroubleTicket template) {
        section("7. The availability flag follows the workload");

        NetworkEngineer target = engineers.findByEmployeeCode("ENG1015")
                .orElseThrow(() -> new IllegalStateException("ENG1015 is missing"));
        check("the engineer starts available", "AVAILABLE",
                String.valueOf(target.getAvailability()));

        // Filled through the workload counter rather than by assigning eight
        // tickets, because it is the counter the flag is derived from.
        while (engineers.getById(target.getId()).canAcceptWork()) {
            engineers.incrementWorkload(target.getId());
        }
        check("the counter reached capacity", "true", String.valueOf(
                engineers.getById(target.getId()).getActiveTicketCount()
                        >= engineers.getById(target.getId()).getMaxTicketCapacity()));
        check("  and the flag had not noticed", "AVAILABLE",
                String.valueOf(engineers.getById(target.getId()).getAvailability()));

        // A Call Drop needs Radio Access Network, which only ENG1015 holds,
        // so the engine has nobody left to fall back on.
        TroubleTicket probe = raise(ticketService, customer, template,
                IncidentCategory.CALL_DROP);
        check("a full engineer is no candidate", "BusinessException", nameOfThrown(
                () -> service.autoAssign(serviceDesk, probe.getTicketNumber())));
        // With the flag still stale the count is all that stands in the way,
        // which is the case the count exists to cover.
        check("  and naming them is refused on the count alone", "BusinessException",
                nameOfThrown(() -> service.assign(serviceDesk, probe.getTicketNumber(),
                        "ENG1015")));
        check("  the refusal quoting the numbers", "true", String.valueOf(messageOfThrown(
                () -> service.assign(serviceDesk, probe.getTicketNumber(), "ENG1015"))
                .contains("of a maximum")));

        check("refreshing turns them busy", "true",
                String.valueOf(engineers.refreshAvailability(target.getId())));
        check("  which the roster now shows", "BUSY",
                String.valueOf(engineers.getById(target.getId()).getAvailability()));
        check("  refreshing again changes nothing", "false",
                String.valueOf(engineers.refreshAvailability(target.getId())));
        check("  and the refusal now quotes the flag", "true", String.valueOf(messageOfThrown(
                () -> service.assign(serviceDesk, probe.getTicketNumber(), "ENG1015"))
                .contains("busy")));

        engineers.decrementWorkload(target.getId());
        check("freeing a slot turns them available again", "AVAILABLE", String.valueOf(
                refreshAndRead(engineers, target.getId())));

        // A manager's ON_LEAVE means something the workload cannot know, so
        // it is left exactly as set.
        NetworkEngineer onLeave = engineers.findByEmployeeCode("ENG1042")
                .orElseThrow(() -> new IllegalStateException("ENG1042 is missing"));
        check("an engineer on leave is left alone", "false",
                String.valueOf(engineers.refreshAvailability(onLeave.getId())));
        check("  still on leave", "ON_LEAVE",
                String.valueOf(engineers.getById(onLeave.getId()).getAvailability()));
    }

    /* ---------- 4e. The sweep and its savepoints ---------- */

    private static void verifySweep(DAOFactory factory, EngineerAssignmentService service,
                                    TicketService ticketService, UserSession serviceDesk,
                                    UserSession customer, TroubleTicket template) {
        section("8. Sweeping the queue, one savepoint per ticket");

        TroubleTicketDAO tickets = factory.getTroubleTicketDAO();

        // Three probes: one the engine can place locally, one it can place
        // by widening, and one nobody on duty can take at all.
        String placeable = raise(ticketService, customer, template,
                IncidentCategory.NETWORK_OUTAGE).getTicketNumber();
        String widened = raise(ticketService, customer, template,
                IncidentCategory.BROADBAND).getTicketNumber();
        String unservable = raise(ticketService, customer, template,
                IncidentCategory.ENTERPRISE_LINK).getTicketNumber();

        List<AssignmentResult> outcomes = service.sweepQueue(serviceDesk,
                EngineerAssignmentServiceImpl.MAX_SWEEP);
        check("the sweep considered the whole queue", "true",
                String.valueOf(outcomes.size() >= 3));

        check("the local fault was placed", "true",
                String.valueOf(outcomeFor(outcomes, placeable).isAssigned()));
        check("the remote fault was placed", "true",
                String.valueOf(outcomeFor(outcomes, widened).isAssigned()));
        check("the one nobody can take was skipped", "false",
                String.valueOf(outcomeFor(outcomes, unservable).isAssigned()));
        check("  with a reason naming the skill", "true", String.valueOf(
                outcomeFor(outcomes, unservable).getMessage().contains("Transmission")));

        // The point of the savepoint: the refusal undid its own ticket's work
        // and nothing else. Both placed tickets are still placed.
        check("the failure did not undo the successes", "true", String.valueOf(
                tickets.findByTicketNumber(placeable)
                        .map(one -> one.getAssignedEngineerId() != null).orElse(false)
                        && tickets.findByTicketNumber(widened)
                        .map(one -> one.getAssignedEngineerId() != null).orElse(false)));
        check("  and the skipped one still has nobody", "true", String.valueOf(
                tickets.findByTicketNumber(unservable)
                        .map(one -> one.getAssignedEngineerId() == null).orElse(false)));
        check("  nor left it moved to Assigned", "OPEN", String.valueOf(
                tickets.findByTicketNumber(unservable).map(TroubleTicket::getStatus)
                        .orElse(null)));

        check("a second sweep finds nothing left to place", "true", String.valueOf(
                service.sweepQueue(serviceDesk, 5).stream()
                        .noneMatch(AssignmentResult::isAssigned)));
        check("the sweep is capped", "50",
                String.valueOf(EngineerAssignmentServiceImpl.MAX_SWEEP));
        check("  and a customer cannot run one", "AuthorizationException",
                nameOfThrown(() -> service.sweepQueue(customer, 5)));
    }

    /* ---------- 5. The database's own version ---------- */

    private static void verifyStoredProcedure(DAOFactory factory) {
        section("9. The same transaction inside the database");

        // Outside the rolled back transaction on purpose. sp_assign_engineer
        // runs START TRANSACTION and COMMIT of its own, which in MySQL would
        // commit whatever the caller had open, so a savepoint here would
        // protect nothing. That is also why the application's own path is the
        // Java one: it composes with the transactions around it.
        EngineerAssignmentService service = new EngineerAssignmentServiceImpl(factory,
                TicketEventPublisher.getInstance());
        TroubleTicketDAO tickets = factory.getTroubleTicketDAO();
        NetworkEngineerDAO engineers = factory.getNetworkEngineerDAO();
        UserSession serviceDesk = sessionFor(Role.SERVICE_DESK, "vfy-sdesk", null, null);

        // The refusal paths return before the procedure opens a transaction,
        // so these write nothing at all.
        ReportDAO.ProcedureOutcome onLeave = service.assignViaProcedure(serviceDesk,
                UNASSIGNED_TICKET, "ENG1042");
        check("the procedure refuses an engineer on leave", "false",
                String.valueOf(onLeave.isSuccess()));
        check("  saying which state they are in", "true",
                String.valueOf(onLeave.getMessage().contains("ON_LEAVE")));

        TroubleTicket worked = tickets.findByTicketNumber(ASSIGNED_TICKET)
                .orElseThrow(() -> new IllegalStateException(
                        "Seed ticket " + ASSIGNED_TICKET + " is missing"));
        check("the seeded ticket is already being worked", "IN_PROGRESS",
                String.valueOf(worked.getStatus()));

        // The success path commits, so it runs against a seeded ticket that
        // is handed back afterwards rather than a probe that would have to
        // be deleted.
        TroubleTicket subject = tickets.findByTicketNumber(PROCEDURE_TICKET)
                .orElseThrow(() -> new IllegalStateException(
                        "Seed ticket " + PROCEDURE_TICKET + " is missing"));
        try {
            TroubleTicket ready = handBack(tickets, subject);
            check("the seeded ticket is free to assign", "OPEN",
                    String.valueOf(ready.getStatus()));
            check("  and held by nobody", "true",
                    String.valueOf(ready.getAssignedEngineerId() == null));

            ReportDAO.ProcedureOutcome success = service.assignViaProcedure(serviceDesk,
                    PROCEDURE_TICKET, PROCEDURE_ENGINEER);
            check("the procedure assigns", "true", String.valueOf(success.isSuccess()));
            check("  and says who to", "true",
                    String.valueOf(success.getMessage().contains(PROCEDURE_ENGINEER)));

            TroubleTicket after = tickets.getById(subject.getId());
            check("the ticket is Assigned", "ASSIGNED", String.valueOf(after.getStatus()));
            check("  with an engineer", PROCEDURE_ENGINEER,
                    engineers.findById(after.getAssignedEngineerId())
                            .map(NetworkEngineer::getEmployeeCode).orElse("(none)"));
            check("  a history row", "ASSIGNED", String.valueOf(
                    newestHistory(factory, after).getNewStatus()));
            check("  a notification", "true", String.valueOf(
                    factory.getNotificationDAO().findByTicketId(after.getId()).stream()
                            .anyMatch(sent -> sent.getMessage().contains(PROCEDURE_ENGINEER))));
            check("  and an audit row", "ENGINEER_ASSIGNED",
                    newestAudit(factory.getAuditLogDAO(), after).getAction());
        } finally {
            TroubleTicket restored = handBack(tickets, subject);
            check("the seeded ticket was handed back", "true", String.valueOf(
                    restored.getStatus() == TicketStatus.OPEN
                            && restored.getAssignedEngineerId() == null));
        }
    }

    /** Puts a ticket back to open and unassigned. */
    private static TroubleTicket handBack(TroubleTicketDAO tickets, TroubleTicket ticket) {
        TroubleTicket current = tickets.getById(ticket.getId());
        current.setStatus(TicketStatus.OPEN);
        current.setAssignedEngineerId(null);
        tickets.update(current);
        return tickets.getById(current.getId());
    }

    /* ---------- Reading and shaping ---------- */

    private static NetworkEngineer engineer(String code, Specialization specialization,
                                            Region region, int experienceYears, int activeCount,
                                            EngineerAvailability availability) {
        NetworkEngineer built = new NetworkEngineer(code, "Engineer " + code,
                code.toLowerCase() + "@example.test", specialization, region, experienceYears);
        built.setId((long) Math.abs(code.hashCode()));
        built.setActiveTicketCount(activeCount);
        built.setAvailability(availability);
        return built;
    }

    private static String codesOf(List<EngineerMatch> matches) {
        return matches.stream()
                .map(EngineerMatch::getEmployeeCode)
                .collect(Collectors.joining(", "));
    }

    private static String codesOfExact(List<EngineerMatch> matches) {
        return matches.stream()
                .filter(match -> match.getTier() == MatchTier.EXACT)
                .map(EngineerMatch::getEmployeeCode)
                .collect(Collectors.joining(", "));
    }

    private static String codesOfDto(List<EngineerRecommendationDTO> views) {
        return views.stream()
                .map(EngineerRecommendationDTO::getEmployeeCode)
                .collect(Collectors.joining(", "));
    }

    private static String tiersOf(List<EngineerMatch> matches) {
        return matches.stream()
                .map(match -> match.getTier().name())
                .collect(Collectors.toList())
                .toString();
    }

    private static String namesOfEngineers(List<NetworkEngineer> roster) {
        return roster.stream()
                .map(NetworkEngineer::getEmployeeCode)
                .collect(Collectors.joining(", "));
    }

    private static int workloadOf(NetworkEngineerDAO engineers, String employeeCode) {
        return engineers.findByEmployeeCode(employeeCode)
                .map(NetworkEngineer::getActiveTicketCount)
                .orElse(-1);
    }

    private static EngineerAvailability refreshAndRead(NetworkEngineerDAO engineers,
                                                       Long engineerId) {
        engineers.refreshAvailability(engineerId);
        return engineers.getById(engineerId).getAvailability();
    }

    private static boolean isPriorityOrdered(List<TroubleTicket> queue) {
        for (int index = 1; index < queue.size(); index++) {
            if (queue.get(index).getPriority().getWeight()
                    > queue.get(index - 1).getPriority().getWeight()) {
                return false;
            }
        }
        return true;
    }

    private static AssignmentResult outcomeFor(List<AssignmentResult> outcomes,
                                               String ticketNumber) {
        return outcomes.stream()
                .filter(one -> ticketNumber.equals(one.getTicketNumber()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "The sweep did not consider " + ticketNumber));
    }

    private static TicketStatusHistory newestHistory(DAOFactory factory, TroubleTicket ticket) {
        List<TicketStatusHistory> trail =
                factory.getTicketStatusHistoryDAO().findByTicketId(ticket.getId());
        if (trail.isEmpty()) {
            throw new IllegalStateException("No history for " + ticket.getTicketNumber());
        }
        return trail.get(trail.size() - 1);
    }

    private static AuditLog newestAudit(AuditLogDAO auditLog, TroubleTicket ticket) {
        List<AuditLog> rows = auditLog.findByEntity("TROUBLE_TICKET", ticket.getTicketNumber());
        if (rows.isEmpty()) {
            throw new IllegalStateException("No audit rows for " + ticket.getTicketNumber());
        }
        return rows.get(0);
    }

    /**
     * Who the assignment told, ignoring the earlier notice that the ticket
     * had been raised at all. Sorted, because who was told matters and the
     * order they were written in does not.
     */
    private static String rolesTold(NotificationDAO inbox, TroubleTicket ticket) {
        List<String> roles = new ArrayList<String>();
        for (Notification sent : notificationsOfType(inbox, ticket,
                NotificationType.ENGINEER_ASSIGNED)) {
            roles.add(sent.getRecipientRole().name());
        }
        Collections.sort(roles);
        return roles.toString();
    }

    private static List<Notification> notificationsOfType(NotificationDAO inbox,
                                                          TroubleTicket ticket,
                                                          NotificationType type) {
        return inbox.findByTicketId(ticket.getId()).stream()
                .filter(sent -> sent.getNotificationType() == type)
                .collect(Collectors.toList());
    }

    /* ---------- Shared ---------- */

    private static TroubleTicket raise(TicketService service, UserSession customer,
                                       TroubleTicket template, IncidentCategory category) {
        return raise(service, customer, template, category, DESCRIPTION, true);
    }

    /**
     * @param disposable whether the ticket should be swept up afterwards,
     *                   which is true of everything except the reserved probe
     */
    private static TroubleTicket raise(TicketService service, UserSession customer,
                                       TroubleTicket template, IncidentCategory category,
                                       String description, boolean disposable) {
        List<TelecomService> offered =
                service.ticketableServicesFor(customer, template.getCustomerId());
        if (offered.isEmpty()) {
            throw new IllegalStateException("The seeded customer has no ticketable service");
        }
        TroubleTicket raised = service.raise(customer,
                new TicketRequest(offered.get(0).getId(), category, description));
        if (disposable) {
            ISSUED.add(raised.getTicketNumber());
        }
        return raised;
    }

    private static UserSession sessionFor(Role role, String username, Long customerId,
                                          Long engineerId) {
        UserAccount account = new UserAccount(username, "Verification Actor",
                username + "@example.test", role);
        account.setId(-1L);
        return new UserSession(account, null, customerId, engineerId);
    }

    private static int countIssuedTickets(TroubleTicketDAO tickets) {
        return disposableStrays(tickets).size();
    }

    /**
     * Last resort cleanup. The rollback should have dealt with every probe,
     * so anything found here means a check threw outside the transaction.
     */
    private static int removeIssuedTickets(TroubleTicketDAO tickets) {
        int removed = 0;
        for (TroubleTicket stray : disposableStrays(tickets)) {
            if (tickets.deleteById(stray.getId())) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Probes still in the table, identified by description rather than by
     * number alone.
     *
     * <p>A number is not proof of identity here. Numbers are issued as one
     * past the highest stored, so a probe rolled back early in the run frees
     * its number for the reserved ticket raised later, and a sweep going by
     * number would delete the very ticket the next run depends on.</p>
     */
    private static List<TroubleTicket> disposableStrays(TroubleTicketDAO tickets) {
        List<TroubleTicket> strays = new ArrayList<TroubleTicket>();
        for (String number : ISSUED) {
            Optional<TroubleTicket> found = tickets.findByTicketNumber(number);
            if (found.isPresent() && DESCRIPTION.equals(found.get().getDescription())) {
                strays.add(found.get());
            }
        }
        return strays;
    }

    private static String nameOfThrown(Runnable work) {
        try {
            work.run();
            return "(nothing thrown)";
        } catch (RuntimeException thrown) {
            return thrown.getClass().getSimpleName();
        }
    }

    private static String messageOfThrown(Runnable work) {
        try {
            work.run();
            return "(nothing thrown)";
        } catch (RuntimeException thrown) {
            return thrown.getMessage() == null ? "(no message)" : thrown.getMessage();
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("  " + title);
    }

    private static void check(String label, String expected, String actual) {
        checksRun++;
        boolean passed = expected.equals(actual);
        if (!passed) {
            checksFailed++;
        }
        System.out.println(String.format("    [%s] %-48s expected=%-30s actual=%s",
                passed ? "PASS" : "FAIL", label, expected, actual));
    }
}
