package com.amdocs.telecom.util;

import com.amdocs.telecom.dao.AuditLogDAO;
import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.EscalationHistoryDAO;
import com.amdocs.telecom.dao.NotificationDAO;
import com.amdocs.telecom.dao.ReportDAO;
import com.amdocs.telecom.dao.TicketStatusHistoryDAO;
import com.amdocs.telecom.dao.TransactionTemplate;
import com.amdocs.telecom.dao.TroubleTicketDAO;
import com.amdocs.telecom.model.AuditLog;
import com.amdocs.telecom.model.EscalationHistory;
import com.amdocs.telecom.model.Notification;
import com.amdocs.telecom.model.SLAConfiguration;
import com.amdocs.telecom.model.TicketStatusHistory;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.UserAccount;
import com.amdocs.telecom.model.enums.EscalationLevel;
import com.amdocs.telecom.model.enums.IncidentCategory;
import com.amdocs.telecom.model.enums.NotificationType;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.model.enums.Role;
import com.amdocs.telecom.model.enums.SLAStatus;
import com.amdocs.telecom.model.enums.Severity;
import com.amdocs.telecom.model.enums.TicketStatus;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.EscalationService;
import com.amdocs.telecom.service.SlaService;
import com.amdocs.telecom.service.escalation.EscalationCandidate;
import com.amdocs.telecom.service.escalation.EscalationOutcome;
import com.amdocs.telecom.service.escalation.EscalationPolicy;
import com.amdocs.telecom.service.escalation.EscalationTrigger;
import com.amdocs.telecom.service.event.TicketEventPublisher;
import com.amdocs.telecom.service.impl.EscalationServiceImpl;
import com.amdocs.telecom.service.impl.SlaServiceImpl;
import com.amdocs.telecom.service.sla.ContinuousSlaClock;
import com.amdocs.telecom.service.sla.SlaClock;
import com.amdocs.telecom.service.sla.SlaEvaluation;

import java.sql.Savepoint;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.stream.Collectors;

/**
 * Exercises the escalation ladder of section 9.
 *
 * <p>The ladder and the policy are checked first without touching the
 * database. That is not a shortcut: the policy is a pure function of a
 * ticket's SLA standing, and a ticket sitting at exactly the at risk
 * threshold, or one that missed its response but not its resolution, is far
 * easier to construct in memory than to arrange in a table. Everything after
 * section 3 runs against the real schema.</p>
 *
 * <p>Nothing is left behind. Every Java escalation happens inside one
 * transaction rolled back to a savepoint, and the probe tickets go with it.
 * The stored procedure is the exception, because it commits a transaction of
 * its own; it runs against a seeded ticket and puts it back afterwards.</p>
 */
public final class EscalationVerification {

    private EscalationVerification() {
        throw new AssertionError("EscalationVerification is not instantiable");
    }

    /** A seeded ticket with no engineer on it. */
    private static final String UNASSIGNED_TICKET = "TT-2026-004521";

    /**
     * The seeded ticket the stored procedure escalates for real.
     *
     * <p>A seeded ticket rather than a probe, because the procedure commits
     * and the trail it writes is append only. This one already exists and
     * always will, so the rows it gathers stay attached to the ticket they
     * describe. Its level and status are put back when the section
     * finishes.</p>
     */
    private static final String PROCEDURE_TICKET = "TT-2026-004523";

    /*
     * Probe numbers outside the live series, so they can never be confused
     * with a real ticket and never take a number the generator would issue.
     */
    private static final String BREACHED_PROBE = "TT-9999-997001";
    private static final String CRITICAL_PROBE = "TT-9999-997002";
    private static final String HIGH_PROBE = "TT-9999-997003";
    private static final String LOW_PROBE = "TT-9999-997004";
    private static final String ORPHAN_PROBE = "TT-9999-997005";

    private static final List<String> PROBES = Arrays.asList(BREACHED_PROBE, CRITICAL_PROBE,
            HIGH_PROBE, LOW_PROBE, ORPHAN_PROBE);

    private static final String PROBE_ACTOR = "vfy-sdesk";

    /** The CRITICAL band as seeded: respond in 15 minutes, resolve in 120. */
    private static final SLAConfiguration CRITICAL_BAND =
            new SLAConfiguration(Priority.CRITICAL, 15, 120);

    private static final SlaClock CLOCK = ContinuousSlaClock.getInstance();

    private static int checksRun;
    private static int checksFailed;

    /**
     * Runs every check and prints a report.
     *
     * @return 0 when everything passed, 1 otherwise
     */
    public static int execute() {
        checksRun = 0;
        checksFailed = 0;

        DAOFactory factory = DAOFactory.getInstance();

        System.out.println("  Escalation verification");
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println();

        try {
            verifyLadder();
            verifyPolicy();
            verifyQueueOrdering();
            verifyRefusals(factory);
            verifyOneEscalation(factory);
            verifySweep(factory);
            verifyStoredProcedure(factory);
        } catch (RuntimeException failure) {
            checksFailed++;
            System.out.println("  Verification aborted: " + failure);
            AppLogger.error(EscalationVerification.class, "Escalation verification aborted",
                    failure);
        } finally {
            int strays = removeProbes(factory.getTroubleTicketDAO());
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
            System.out.println("  The escalation ladder is working against the live database.");
            AppLogger.info(EscalationVerification.class,
                    "Escalation verification passed " + checksRun + " checks");
        } else {
            System.out.println("  " + checksFailed + " check(s) failed. See the detail above.");
            AppLogger.warn(EscalationVerification.class,
                    "Escalation verification had " + checksFailed + " failure(s)");
        }
        System.out.println();
        return checksFailed == 0 ? 0 : 1;
    }

    /* ---------- 1. The ladder ---------- */

    private static void verifyLadder() {
        section("1. The four rungs of section 9");

        check("the ladder has four rungs", "4",
                String.valueOf(EscalationLevel.values().length));
        check("  in the order the document draws them",
                "Engineer, Team Lead, Network Manager, Operations Manager",
                namesOf(EscalationLevel.values()));
        check("  numbered from the bottom", "[1, 2, 3, 4]", levelsOf(EscalationLevel.values()));
        check("  and coded for the schema", "[L1, L2, L3, L4]",
                codesOf(EscalationLevel.values()));

        check("engineer hands up to the team lead", "TEAM_LEAD",
                nextOf(EscalationLevel.ENGINEER));
        check("  team lead to the network manager", "NETWORK_MANAGER",
                nextOf(EscalationLevel.TEAM_LEAD));
        check("  network manager to operations", "OPERATIONS_MANAGER",
                nextOf(EscalationLevel.NETWORK_MANAGER));
        check("  and operations has nobody above them", "(none)",
                nextOf(EscalationLevel.OPERATIONS_MANAGER));

        check("only the last rung is the top", "false",
                String.valueOf(EscalationLevel.ENGINEER.isTopLevel()));
        check("  which operations is", "true",
                String.valueOf(EscalationLevel.OPERATIONS_MANAGER.isTopLevel()));
    }

    /* ---------- 2. The policy ---------- */

    private static void verifyPolicy() {
        section("2. What the SLA standing warrants");

        EscalationPolicy policy = new EscalationPolicy();
        LocalDateTime raisedAt = LocalDateTime.of(2026, 4, 1, 9, 0);

        // Comfortably inside both windows: ten minutes into a fifteen minute
        // response window and a two hour resolution window.
        SlaEvaluation healthy = verdict(inFlight(raisedAt, null), raisedAt.plusMinutes(10));
        check("a ticket inside its windows has no trigger", "(none)",
                triggerOf(policy, healthy));
        check("  and warrants no more than its engineer", "ENGINEER",
                policy.warrantedLevel(healthy).name());
        check("  so there is nothing to do", "(none)",
                nextOf(policy, EscalationLevel.ENGINEER, healthy));
        check("  and it is not behind", "false",
                String.valueOf(policy.isBehind(EscalationLevel.ENGINEER, healthy)));

        // Twenty minutes in: the response window has run out, the resolution
        // window has barely started.
        SlaEvaluation unanswered = verdict(inFlight(raisedAt, null), raisedAt.plusMinutes(20));
        check("an unanswered ticket has missed its response", "true",
                String.valueOf(unanswered.isResponseBreached()));
        check("  which is still within SLA overall", "WITHIN_SLA",
                unanswered.getLiveStatus().name());
        check("  the trigger is the response", "RESPONSE_MISSED",
                triggerOf(policy, unanswered));
        check("  and it warrants the team lead", "TEAM_LEAD",
                policy.warrantedLevel(unanswered).name());

        // A response that arrived, but late, is still a missed response.
        SlaEvaluation answeredLate = verdict(
                inFlight(raisedAt, raisedAt.plusMinutes(40)), raisedAt.plusMinutes(45));
        check("a late first response counts too", "RESPONSE_MISSED",
                triggerOf(policy, answeredLate));
        SlaEvaluation answeredInTime = verdict(
                inFlight(raisedAt, raisedAt.plusMinutes(5)), raisedAt.plusMinutes(45));
        check("  a punctual one does not", "(none)", triggerOf(policy, answeredInTime));

        // 100 of the 120 minutes gone, which is the seeded 80 per cent
        // threshold, so the warning band has been entered.
        SlaEvaluation atRisk = verdict(
                inFlight(raisedAt, raisedAt.plusMinutes(5)), raisedAt.plusMinutes(100));
        check("at the threshold the ticket is at risk", "AT_RISK",
                atRisk.getLiveStatus().name());
        check("  the trigger is the resolution window", "RESOLUTION_AT_RISK",
                triggerOf(policy, atRisk));
        check("  and it warrants the network manager", "NETWORK_MANAGER",
                policy.warrantedLevel(atRisk).name());

        SlaEvaluation breached = verdict(
                inFlight(raisedAt, raisedAt.plusMinutes(5)), raisedAt.plusMinutes(150));
        check("past the deadline the ticket is breached", "BREACHED",
                breached.getLiveStatus().name());
        check("  the trigger says so", "RESOLUTION_BREACHED", triggerOf(policy, breached));
        check("  and it goes as far as the ladder goes", "OPERATIONS_MANAGER",
                policy.warrantedLevel(breached).name());

        // The worst reason wins, so a ticket that both missed its response
        // and is now breached warrants the top rather than the second rung.
        SlaEvaluation both = verdict(inFlight(raisedAt, null), raisedAt.plusMinutes(150));
        check("the worst reason decides", "RESOLUTION_BREACHED", triggerOf(policy, both));
        check("  even though the response was missed as well", "true",
                String.valueOf(both.isResponseBreached()));

        // Waiting on the customer pauses the warning but not the breach,
        // which is the distinction the SLA monitor already draws.
        TroubleTicket waiting = inFlight(raisedAt, raisedAt.plusMinutes(5));
        waiting.setStatus(TicketStatus.PENDING_CUSTOMER);
        check("at risk while waiting on the customer is left alone", "(none)",
                triggerOf(policy, verdict(waiting, raisedAt.plusMinutes(100))));
        check("  but a breach while waiting is not", "RESOLUTION_BREACHED",
                triggerOf(policy, verdict(waiting, raisedAt.plusMinutes(150))));

        TroubleTicket resolved = inFlight(raisedAt, raisedAt.plusMinutes(5));
        resolved.setStatus(TicketStatus.RESOLVED);
        resolved.setResolutionDate(raisedAt.plusMinutes(200));
        check("a finished ticket is never escalated", "(none)",
                triggerOf(policy, verdict(resolved, raisedAt.plusMinutes(250))));
        check("  although it did breach", "BREACHED",
                verdict(resolved, raisedAt.plusMinutes(250)).getLiveStatus().name());
        check("no verdict at all is no trigger", "(none)", triggerOf(policy, null));
        check("  and warrants the bottom rung", "ENGINEER",
                policy.warrantedLevel(null).name());

        section("2b. One rung at a time");

        check("a breached ticket with its engineer moves to the team lead", "TEAM_LEAD",
                nextOf(policy, EscalationLevel.ENGINEER, breached));
        check("  not straight to the top", "false", String.valueOf(
                EscalationLevel.OPERATIONS_MANAGER.name()
                        .equals(nextOf(policy, EscalationLevel.ENGINEER, breached))));
        check("  then to the network manager", "NETWORK_MANAGER",
                nextOf(policy, EscalationLevel.TEAM_LEAD, breached));
        check("  then to operations", "OPERATIONS_MANAGER",
                nextOf(policy, EscalationLevel.NETWORK_MANAGER, breached));
        check("  and then stops", "(none)",
                nextOf(policy, EscalationLevel.OPERATIONS_MANAGER, breached));

        check("a missed response stops at the team lead", "(none)",
                nextOf(policy, EscalationLevel.TEAM_LEAD, unanswered));
        check("an at risk ticket stops at the network manager", "(none)",
                nextOf(policy, EscalationLevel.NETWORK_MANAGER, atRisk));
        check("  and is not pulled back down when it sits higher", "(none)",
                nextOf(policy, EscalationLevel.OPERATIONS_MANAGER, atRisk));
        check("a row with no level is treated as being at the bottom", "TEAM_LEAD",
                nextOf(policy, null, breached));

        // Asking twice gives the same answer, which is what makes running the
        // sweep again safe.
        check("the same question twice gives the same answer", "true",
                String.valueOf(nextOf(policy, EscalationLevel.ENGINEER, breached)
                        .equals(nextOf(policy, EscalationLevel.ENGINEER, breached))));

        section("2c. The judgement as a whole");

        TroubleTicket subject = inFlight(raisedAt, null);
        subject.setTicketNumber("TT-TEST-000001");
        subject.setEscalationLevel(EscalationLevel.ENGINEER);
        Optional<EscalationCandidate> candidate = policy.assess(subject,
                verdict(subject, raisedAt.plusMinutes(150)));
        check("a behind ticket is a candidate", "true", String.valueOf(candidate.isPresent()));
        check("  from where it is", "ENGINEER",
                candidate.map(one -> one.getFromLevel().name()).orElse("(none)"));
        check("  to the next rung up", "TEAM_LEAD",
                candidate.map(one -> one.getToLevel().name()).orElse("(none)"));
        check("  with the reason recorded", "RESOLUTION_BREACHED",
                candidate.map(one -> one.getTrigger().name()).orElse("(none)"));
        check("  phrased for the trail",
                "Escalated automatically because the resolution deadline has passed.",
                candidate.map(EscalationCandidate::getReason).orElse("(none)"));
        check("  and carrying the ticket itself", "TT-TEST-000001",
                candidate.map(EscalationCandidate::getTicketNumber).orElse("(none)"));

        TroubleTicket topped = inFlight(raisedAt, null);
        topped.setEscalationLevel(EscalationLevel.OPERATIONS_MANAGER);
        check("a ticket at the top is no candidate", "false", String.valueOf(policy
                .assess(topped, verdict(topped, raisedAt.plusMinutes(150))).isPresent()));
        check("  and the model agrees it cannot climb", "false",
                String.valueOf(topped.canEscalate()));
        check("a healthy ticket is no candidate", "false", String.valueOf(policy
                .assess(subject, healthy).isPresent()));
        check("no ticket at all is no candidate", "false",
                String.valueOf(policy.assess(null, breached).isPresent()));

        check("the standing is explained for the console", "true", String.valueOf(policy
                .describe(EscalationLevel.ENGINEER, breached)
                .contains("calls for Operations Manager")));
        check("  including when nothing is wrong", "true", String.valueOf(policy
                .describe(EscalationLevel.ENGINEER, healthy)
                .contains("which its SLA standing supports")));
        check("  and when it has already been raised far enough", "true", String.valueOf(policy
                .describe(EscalationLevel.OPERATIONS_MANAGER, atRisk)
                .contains("Already at Operations Manager")));
    }

    /* ---------- 3. The queue ---------- */

    private static void verifyQueueOrdering() {
        section("3. Critical tickets first, as section 9 requires");

        EscalationPolicy policy = new EscalationPolicy();
        LocalDateTime raisedAt = LocalDateTime.of(2026, 4, 1, 9, 0);
        LocalDateTime now = raisedAt.plusDays(30);

        // Deliberately offered in the wrong order, so the queue has work to
        // do rather than merely preserving what it was given.
        List<EscalationCandidate> offered = new ArrayList<EscalationCandidate>();
        offered.add(candidateAt(policy, "TT-TEST-000LOW", Priority.LOW, raisedAt, now));
        offered.add(candidateAt(policy, "TT-TEST-000CRT", Priority.CRITICAL, raisedAt, now));
        offered.add(candidateAt(policy, "TT-TEST-000MED", Priority.MEDIUM, raisedAt, now));
        offered.add(candidateAt(policy, "TT-TEST-000HGH", Priority.HIGH, raisedAt, now));

        check("four candidates to order", "4", String.valueOf(offered.size()));
        check("  offered lowest first",
                "TT-TEST-000LOW, TT-TEST-000CRT, TT-TEST-000MED, TT-TEST-000HGH",
                numbersOf(offered));
        check("the queue hands back the most urgent first",
                "TT-TEST-000CRT, TT-TEST-000HGH, TT-TEST-000MED, TT-TEST-000LOW",
                numbersOf(poll(offered)));

        // Among equals the ticket that has waited longest goes first, which
        // is what stops a busy band from starving its own oldest ticket.
        List<EscalationCandidate> sameBand = Arrays.asList(
                candidateAt(policy, "TT-TEST-000NEW", Priority.CRITICAL,
                        raisedAt.plusHours(6), now),
                candidateAt(policy, "TT-TEST-000OLD", Priority.CRITICAL, raisedAt, now),
                candidateAt(policy, "TT-TEST-000MID", Priority.CRITICAL,
                        raisedAt.plusHours(3), now));
        check("equal urgency is broken by age",
                "TT-TEST-000OLD, TT-TEST-000MID, TT-TEST-000NEW", numbersOf(poll(sameBand)));

        check("one candidate is a queue of one", "TT-TEST-000CRT",
                numbersOf(poll(offered.subList(1, 2))));
        check("nothing behind is an empty queue", "",
                numbersOf(poll(Collections.<EscalationCandidate>emptyList())));
    }

    /* ---------- 4. Refusals ---------- */

    private static void verifyRefusals(DAOFactory factory) {
        section("4. What escalation refuses");

        EscalationService service = new EscalationServiceImpl(factory, new SlaServiceImpl(),
                TicketEventPublisher.getInstance());
        TroubleTicketDAO tickets = factory.getTroubleTicketDAO();

        UserSession serviceDesk = sessionFor(Role.SERVICE_DESK, PROBE_ACTOR, null, null);
        UserSession engineer = sessionFor(Role.NETWORK_ENGINEER, "vfy-engineer", null, 1L);
        UserSession manager = sessionFor(Role.NETWORK_MANAGER, "vfy-manager", null, null);
        UserSession customer = sessionFor(Role.CUSTOMER, "vfy-customer", 1L, null);

        check("a customer may not escalate", "AuthorizationException",
                nameOfThrown(() -> service.escalate(customer, UNASSIGNED_TICKET, "Please")));
        check("nobody signed in may not escalate", "AuthenticationException",
                nameOfThrown(() -> service.escalate(null, UNASSIGNED_TICKET, "Please")));
        check("the service desk may", "true",
                String.valueOf(canReach(() -> service.assess(serviceDesk, UNASSIGNED_TICKET))));
        check("  an engineer may, to ask for help", "true",
                String.valueOf(canReach(() -> service.assess(engineer, UNASSIGNED_TICKET))));
        check("  and so may the network manager", "true",
                String.valueOf(canReach(() -> service.assess(manager, UNASSIGNED_TICKET))));

        check("a blank reason is refused", "ValidationException",
                nameOfThrown(() -> service.escalate(serviceDesk, UNASSIGNED_TICKET, "   ")));
        check("no reason at all is refused", "ValidationException",
                nameOfThrown(() -> service.escalate(serviceDesk, UNASSIGNED_TICKET, null)));
        check("a missing ticket number is refused", "ValidationException",
                nameOfThrown(() -> service.escalate(serviceDesk, "  ", "Please")));
        check("an unknown ticket is refused", "ResourceNotFoundException",
                nameOfThrown(() -> service.escalate(serviceDesk, "TT-0000-000000", "Please")));

        // A ticket nobody owns has nobody to escalate past. The remedy is
        // assignment, and the refusal says so.
        check("a ticket with no engineer is refused", "BusinessException",
                nameOfThrown(() -> service.escalate(serviceDesk, UNASSIGNED_TICKET, "Please")));
        check("  and is told what to do instead", "true",
                String.valueOf(messageOfThrown(() ->
                        service.escalate(serviceDesk, UNASSIGNED_TICKET, "Please"))
                        .contains("Assign an engineer first")));
        check("  so it is not a candidate either", "false", String.valueOf(
                service.assess(serviceDesk, UNASSIGNED_TICKET).isPresent()));

        Optional<TroubleTicket> finished = anyFinishedTicket(tickets);
        check("a seeded ticket that has finished", "true", String.valueOf(finished.isPresent()));
        if (finished.isPresent()) {
            String number = finished.get().getTicketNumber();
            check("  cannot be escalated", "BusinessException",
                    nameOfThrown(() -> service.escalate(serviceDesk, number, "Please")));
            check("  because it has finished", "true",
                    String.valueOf(messageOfThrown(() ->
                            service.escalate(serviceDesk, number, "Please"))
                            .contains("cannot be escalated")));
        }

        check("the queue excludes everything it would refuse", "true", String.valueOf(
                service.candidates(manager, 0).stream().allMatch(one ->
                        one.getTicket().getAssignedEngineerId() != null
                                && !one.getTicket().getStatus().isFinished()
                                && !one.getFromLevel().isTopLevel())));
        check("  and asking changes nothing", "true",
                String.valueOf(sameCandidates(service, manager)));
    }

    /* ---------- 5. One escalation, end to end ---------- */

    private static void verifyOneEscalation(DAOFactory factory) {
        section("5. Escalating one ticket");

        TroubleTicketDAO tickets = factory.getTroubleTicketDAO();
        EscalationHistoryDAO escalations = factory.getEscalationHistoryDAO();
        SlaService sla = new SlaServiceImpl();
        EscalationService service = new EscalationServiceImpl(factory, sla,
                TicketEventPublisher.getInstance());
        UserSession serviceDesk = sessionFor(Role.SERVICE_DESK, PROBE_ACTOR, null, null);

        TroubleTicket template = tickets.findByTicketNumber(UNASSIGNED_TICKET)
                .orElseThrow(() -> new IllegalStateException(
                        "Seed ticket " + UNASSIGNED_TICKET + " is missing"));
        Long anyEngineer = anyEngineerId(factory);

        TransactionTemplate.run(context -> {
            Savepoint marker = context.savepoint("escalation_verification");
            try {
                TroubleTicket probe = tickets.insert(probeTicket(template, BREACHED_PROBE,
                        Priority.CRITICAL, anyEngineer, sla));
                check("a probe ten days past its deadline", "BREACHED",
                        sla.evaluate(probe).getLiveStatus().name());
                check("  is a candidate", "true", String.valueOf(
                        service.assess(serviceDesk, BREACHED_PROBE).isPresent()));
                // The sentence names the rung the breach warrants, which is
                // the top, while the move about to happen is one rung. Both
                // are true at once: the ladder is climbed a rung at a time
                // towards a level the standing already justifies.
                check("  explained in a sentence", "true", String.valueOf(
                        service.explain(serviceDesk, BREACHED_PROBE)
                                .contains("calls for Operations Manager")));

                EscalationOutcome first = service.escalate(serviceDesk, BREACHED_PROBE,
                        "Customer called to complain");
                check("the escalation happened", "true", String.valueOf(first.isEscalated()));
                check("  one rung, from the engineer", "ENGINEER",
                        first.findFromLevel().map(EscalationLevel::name).orElse("(none)"));
                check("  to the team lead", "TEAM_LEAD",
                        first.findToLevel().map(EscalationLevel::name).orElse("(none)"));
                check("  a person asked for it, not the monitor", "false",
                        String.valueOf(first.isAutomatic()));
                check("  and it reads plainly", "Raised from Engineer to Team Lead",
                        first.getMessage());

                TroubleTicket moved = tickets.getById(probe.getId());
                check("the ticket sits a rung higher", "TEAM_LEAD",
                        String.valueOf(moved.getEscalationLevel()));
                check("  and is now escalated", "ESCALATED", String.valueOf(moved.getStatus()));
                check("  still with the same engineer", "true",
                        String.valueOf(anyEngineer.equals(moved.getAssignedEngineerId())));

                List<EscalationHistory> trail = escalations.findByTicketId(probe.getId());
                check("one line in the escalation trail", "1", String.valueOf(trail.size()));
                check("  recording where it came from", "ENGINEER",
                        String.valueOf(trail.get(0).getFromLevel()));
                check("  and where it went", "TEAM_LEAD",
                        String.valueOf(trail.get(0).getToLevel()));
                check("  the reason given", "Customer called to complain",
                        trail.get(0).getReason());
                check("  who asked", PROBE_ACTOR, trail.get(0).getEscalatedBy());
                check("  and that it was not automatic", "false",
                        String.valueOf(trail.get(0).isAutoEscalated()));
                check("  stamped with a time", "true",
                        String.valueOf(trail.get(0).getEscalationDate() != null));

                TicketStatusHistory status = newestHistory(factory, probe);
                check("the status trail followed", "ASSIGNED -> ESCALATED",
                        status.getOldStatus() + " -> " + status.getNewStatus());
                check("  naming both rungs", "true",
                        String.valueOf(status.getRemarks()
                                .contains("Escalated from Engineer to Team Lead")));

                // The engineer holding it and every network manager, and not
                // the customer: an escalation is an internal reallocation of
                // attention, and telling the customer their ticket has been
                // passed upwards would read as bad news rather than as the
                // help it is.
                check("the engineer and every manager were told",
                        "[NETWORK_ENGINEER, NETWORK_MANAGER]",
                        rolesTold(factory.getNotificationDAO(), probe));
                AuditLog audit = newestAudit(factory.getAuditLogDAO(), probe);
                check("the escalation was audited", "TICKET_ESCALATED", audit.getAction());
                check("  recording the move", "Engineer -> Team Lead",
                        audit.getOldValue() + " -> " + audit.getNewValue());

                section("5b. Climbing the rest of the ladder");

                EscalationOutcome second = service.escalate(serviceDesk, BREACHED_PROBE,
                        "Still nothing after an hour");
                check("the second rung", "TEAM_LEAD -> NETWORK_MANAGER",
                        second.findFromLevel().get() + " -> " + second.findToLevel().get());
                TroubleTicket afterSecond = tickets.getById(probe.getId());
                check("  the status did not move again", "ESCALATED",
                        String.valueOf(afterSecond.getStatus()));
                TicketStatusHistory secondRow = newestHistory(factory, probe);
                check("  which the trail records honestly", "ESCALATED -> ESCALATED",
                        secondRow.getOldStatus() + " -> " + secondRow.getNewStatus());

                EscalationOutcome third = service.escalate(serviceDesk, BREACHED_PROBE,
                        "Needs a decision from operations");
                check("the third rung", "NETWORK_MANAGER -> OPERATIONS_MANAGER",
                        third.findFromLevel().get() + " -> " + third.findToLevel().get());
                check("  which is the top", "true", String.valueOf(
                        tickets.getById(probe.getId()).getEscalationLevel().isTopLevel()));
                check("a fourth escalation is refused", "BusinessException",
                        nameOfThrown(() -> service.escalate(serviceDesk, BREACHED_PROBE,
                                "Anybody else")));
                check("  because there is nobody further", "true", String.valueOf(
                        messageOfThrown(() -> service.escalate(serviceDesk, BREACHED_PROBE,
                                "Anybody else")).contains("top of the ladder")));

                List<EscalationHistory> full = escalations.findByTicketId(probe.getId());
                check("three steps in the trail", "3", String.valueOf(full.size()));
                check("  oldest first", "Engineer, Team Lead, Network Manager",
                        fromLevelsOf(full));
                check("  each starting where the last ended", "true",
                        String.valueOf(isChained(full)));
                check("  counted the same way", "3",
                        String.valueOf(escalations.countByTicketId(probe.getId())));
                check("the service reports the same trail", "3",
                        String.valueOf(service.history(serviceDesk, BREACHED_PROBE).size()));

                section("5c. The guarded update");

                // Each of these is the statement a second operator would run
                // having read the ticket a moment too early.
                check("a stale rung is refused", "false", String.valueOf(
                        tickets.escalate(probe.getId(), EscalationLevel.ENGINEER,
                                EscalationLevel.TEAM_LEAD, TicketStatus.ESCALATED)));
                check("a stale status is refused", "false", String.valueOf(
                        tickets.escalate(probe.getId(), EscalationLevel.OPERATIONS_MANAGER,
                                EscalationLevel.TEAM_LEAD, TicketStatus.ASSIGNED)));
                check("an unknown ticket is refused", "false", String.valueOf(
                        tickets.escalate(-999L, EscalationLevel.ENGINEER,
                                EscalationLevel.TEAM_LEAD, TicketStatus.ASSIGNED)));
                check("the rung and status as read are accepted", "true", String.valueOf(
                        tickets.escalate(probe.getId(), EscalationLevel.OPERATIONS_MANAGER,
                                EscalationLevel.TEAM_LEAD, TicketStatus.ESCALATED)));
                check("  and the row moved", "TEAM_LEAD", String.valueOf(
                        tickets.getById(probe.getId()).getEscalationLevel()));
            } finally {
                context.rollbackTo(marker);
            }
        });

        check("nothing was left behind", "0",
                String.valueOf(countProbes(factory.getTroubleTicketDAO())));
    }

    /* ---------- 6. The sweep ---------- */

    private static void verifySweep(DAOFactory factory) {
        section("6. Sweeping the queue, most urgent first");

        TroubleTicketDAO tickets = factory.getTroubleTicketDAO();
        SlaService sla = new SlaServiceImpl();
        EscalationService service = new EscalationServiceImpl(factory, sla,
                TicketEventPublisher.getInstance());
        UserSession manager = sessionFor(Role.NETWORK_MANAGER, "vfy-manager", null, null);

        TroubleTicket template = tickets.findByTicketNumber(UNASSIGNED_TICKET)
                .orElseThrow(() -> new IllegalStateException(
                        "Seed ticket " + UNASSIGNED_TICKET + " is missing"));
        Long anyEngineer = anyEngineerId(factory);

        TransactionTemplate.run(context -> {
            Savepoint marker = context.savepoint("escalation_sweep");
            try {
                tickets.insert(probeTicket(template, LOW_PROBE, Priority.LOW, anyEngineer, sla));
                tickets.insert(probeTicket(template, CRITICAL_PROBE, Priority.CRITICAL,
                        anyEngineer, sla));
                tickets.insert(probeTicket(template, HIGH_PROBE, Priority.HIGH, anyEngineer,
                        sla));
                tickets.insert(probeTicket(template, ORPHAN_PROBE, Priority.CRITICAL, null, sla));

                List<EscalationCandidate> queue = service.candidates(manager, 0);
                check("the three owned probes are all behind", "true", String.valueOf(
                        numbersOf(queue).contains(CRITICAL_PROBE)
                                && numbersOf(queue).contains(HIGH_PROBE)
                                && numbersOf(queue).contains(LOW_PROBE)));
                check("  the unowned one is not in the queue", "false",
                        String.valueOf(numbersOf(queue).contains(ORPHAN_PROBE)));
                check("  and the queue is in urgency order", "true",
                        String.valueOf(isUrgencyOrdered(queue)));
                check("  critical before high before low", "true", String.valueOf(
                        positionOf(queue, CRITICAL_PROBE) < positionOf(queue, HIGH_PROBE)
                                && positionOf(queue, HIGH_PROBE) < positionOf(queue, LOW_PROBE)));

                check("a limit of one returns the most urgent alone",
                        queue.get(0).getTicketNumber(),
                        service.candidates(manager, 1).get(0).getTicketNumber());
                check("  and an absurd limit is capped rather than trusted",
                        String.valueOf(service.candidates(manager, EscalationServiceImpl.MAX_SWEEP)
                                .size()),
                        String.valueOf(service.candidates(manager, 9999).size()));

                List<EscalationOutcome> swept = service.sweep(manager, EscalationServiceImpl
                        .MAX_SWEEP);
                check("the sweep escalated what it considered", "true", String.valueOf(
                        swept.stream().allMatch(EscalationOutcome::isEscalated)));
                check("  in the same urgency order", "true", String.valueOf(
                        positionOfOutcome(swept, CRITICAL_PROBE)
                                < positionOfOutcome(swept, HIGH_PROBE)
                                && positionOfOutcome(swept, HIGH_PROBE)
                                < positionOfOutcome(swept, LOW_PROBE)));
                check("  every move was the monitor's", "true", String.valueOf(
                        swept.stream().allMatch(EscalationOutcome::isAutomatic)));
                check("  and each moved exactly one rung", "true",
                        String.valueOf(swept.stream().allMatch(one ->
                                one.findToLevel().get().getLevel()
                                        - one.findFromLevel().get().getLevel() == 1)));

                check("the critical probe reached the team lead", "TEAM_LEAD",
                        levelOf(tickets, CRITICAL_PROBE));
                check("  and so did the low one", "TEAM_LEAD", levelOf(tickets, LOW_PROBE));
                check("  the unowned one was left where it was", "ENGINEER",
                        levelOf(tickets, ORPHAN_PROBE));
                check("  and is still open", "OPEN", statusOf(tickets, ORPHAN_PROBE));

                EscalationHistory line = factory.getEscalationHistoryDAO()
                        .findByTicketId(idOf(tickets, CRITICAL_PROBE)).get(0);
                check("the sweep recorded itself as automatic", "true",
                        String.valueOf(line.isAutoEscalated()));
                check("  with the reason the policy gave", "true", String.valueOf(
                        line.getReason().contains("the resolution deadline has passed")));
                check("  and the sweep appears in the automatic list", "true", String.valueOf(
                        service.automatic(manager).stream().anyMatch(
                                row -> row.getTicketId().equals(idOf(tickets, CRITICAL_PROBE)))));

                // A second pass moves each ticket one more rung. That is the
                // whole point of one rung per pass: catching up takes as many
                // passes as there are rungs, and each is recorded.
                List<EscalationOutcome> again = service.sweep(manager,
                        EscalationServiceImpl.MAX_SWEEP);
                check("a second pass moves them on again", "NETWORK_MANAGER",
                        levelOf(tickets, CRITICAL_PROBE));
                check("  from where the first pass left them", "true", String.valueOf(
                        again.stream().allMatch(one ->
                                one.findFromLevel().get() != EscalationLevel.ENGINEER)));
                check("  leaving a second line in the trail", "2", String.valueOf(
                        factory.getEscalationHistoryDAO()
                                .countByTicketId(idOf(tickets, CRITICAL_PROBE))));

                service.sweep(manager, EscalationServiceImpl.MAX_SWEEP);
                check("a third pass reaches the top", "OPERATIONS_MANAGER",
                        levelOf(tickets, CRITICAL_PROBE));
                List<EscalationOutcome> spent = service.sweep(manager,
                        EscalationServiceImpl.MAX_SWEEP);
                check("  and a fourth has nothing left to do", "true", String.valueOf(
                        spent.stream().noneMatch(one ->
                                CRITICAL_PROBE.equals(one.getTicketNumber()))));
                check("  because none of the probes is behind any more", "false",
                        String.valueOf(numbersOf(service.candidates(manager, 0))
                                .contains(CRITICAL_PROBE)));
            } finally {
                context.rollbackTo(marker);
            }
        });

        check("nothing was left behind", "0", String.valueOf(countProbes(tickets)));
    }

    /* ---------- 7. The stored procedure ---------- */

    private static void verifyStoredProcedure(DAOFactory factory) {
        section("7. The same work inside the database");

        // Outside any transaction on purpose. sp_escalate_ticket runs START
        // TRANSACTION and COMMIT of its own, which in MySQL would commit
        // whatever the caller had open, so a savepoint here would protect
        // nothing. That is also why the application's own path is the Java
        // one: it composes with the transactions around it.
        TroubleTicketDAO tickets = factory.getTroubleTicketDAO();
        EscalationService service = new EscalationServiceImpl(factory, new SlaServiceImpl(),
                TicketEventPublisher.getInstance());
        UserSession serviceDesk = sessionFor(Role.SERVICE_DESK, PROBE_ACTOR, null, null);

        TroubleTicket subject = tickets.findByTicketNumber(PROCEDURE_TICKET)
                .orElseThrow(() -> new IllegalStateException(
                        "Seed ticket " + PROCEDURE_TICKET + " is missing"));
        TicketStatus originalStatus = subject.getStatus();
        EscalationLevel originalLevel = subject.getEscalationLevel();

        check("the seeded ticket has an engineer", "true",
                String.valueOf(subject.getAssignedEngineerId() != null));

        try {
            // The refusal path returns before the procedure opens a
            // transaction, so this writes nothing at all.
            restore(tickets, subject, originalStatus, EscalationLevel.OPERATIONS_MANAGER);
            ReportDAO.ProcedureOutcome topped = service.escalateViaProcedure(serviceDesk,
                    PROCEDURE_TICKET, "Nowhere left to go", false);
            check("the procedure refuses the top rung", "false",
                    String.valueOf(topped.isSuccess()));
            check("  saying so plainly", "Ticket is already at the highest escalation level",
                    topped.getMessage());
            check("  and wrote nothing", "OPERATIONS_MANAGER",
                    levelOf(tickets, PROCEDURE_TICKET));

            restore(tickets, subject, originalStatus, EscalationLevel.ENGINEER);
            long trailBefore = factory.getEscalationHistoryDAO()
                    .countByTicketId(subject.getId());

            ReportDAO.ProcedureOutcome moved = service.escalateViaProcedure(serviceDesk,
                    PROCEDURE_TICKET, "Escalated through the database", true);
            check("the procedure escalated the ticket", "true",
                    String.valueOf(moved.isSuccess()));
            check("  reporting where it went", "true",
                    String.valueOf(moved.getMessage().contains("TEAM_LEAD")));
            check("  the row moved one rung", "TEAM_LEAD", levelOf(tickets, PROCEDURE_TICKET));
            check("  and the status with it", "ESCALATED", statusOf(tickets, PROCEDURE_TICKET));

            check("  the trail gained a line", String.valueOf(trailBefore + 1),
                    String.valueOf(factory.getEscalationHistoryDAO()
                            .countByTicketId(subject.getId())));
            List<EscalationHistory> trail = factory.getEscalationHistoryDAO()
                    .findByTicketId(subject.getId());
            EscalationHistory newest = trail.get(trail.size() - 1);
            check("  from the engineer", "ENGINEER", String.valueOf(newest.getFromLevel()));
            check("  to the team lead", "TEAM_LEAD", String.valueOf(newest.getToLevel()));
            check("  recorded as automatic, as asked", "true",
                    String.valueOf(newest.isAutoEscalated()));
            check("  with the reason passed in", "Escalated through the database",
                    newest.getReason());
            check("  and the actor", PROBE_ACTOR, newest.getEscalatedBy());

            // The same action name the Java route writes, so a search of the
            // trail for escalations finds both.
            AuditLog audit = newestAudit(factory.getAuditLogDAO(), subject);
            check("  the procedure audited it too", "TICKET_ESCALATED", audit.getAction());
            check("  recording the move", "ENGINEER -> TEAM_LEAD",
                    audit.getOldValue() + " -> " + audit.getNewValue());

            check("an unknown ticket never reaches the procedure",
                    "ResourceNotFoundException", nameOfThrown(() ->
                            service.escalateViaProcedure(serviceDesk, "TT-0000-000000",
                                    "Please", false)));
            check("  nor does a blank reason", "ValidationException", nameOfThrown(() ->
                    service.escalateViaProcedure(serviceDesk, PROCEDURE_TICKET, " ", false)));
        } finally {
            restore(tickets, subject, originalStatus, originalLevel);
            TroubleTicket back = tickets.getById(subject.getId());
            check("the seeded ticket was put back", String.valueOf(originalStatus) + "/"
                            + String.valueOf(originalLevel),
                    back.getStatus() + "/" + back.getEscalationLevel());
        }
    }

    /* ---------- Probes ---------- */

    /**
     * A probe ticket borrowing a seeded ticket's customer and service keys,
     * so the foreign keys are certain to be valid, and dated far enough back
     * that every band's resolution window has expired.
     *
     * @param engineerId the engineer holding it, or null to make a ticket
     *                   nobody owns
     */
    private static TroubleTicket probeTicket(TroubleTicket template, String ticketNumber,
                                             Priority priority, Long engineerId, SlaService sla) {
        TroubleTicket ticket = new TroubleTicket();
        ticket.setTicketNumber(ticketNumber);
        ticket.setCustomerId(template.getCustomerId());
        ticket.setServiceId(template.getServiceId());
        ticket.setCategory(IncidentCategory.OTHER);
        ticket.setDescription("Escalation verification probe");
        ticket.setPriority(priority);
        ticket.setSeverity(Severity.MAJOR);
        ticket.setStatus(engineerId == null ? TicketStatus.OPEN : TicketStatus.ASSIGNED);
        ticket.setAssignedEngineerId(engineerId);
        ticket.setEscalationLevel(EscalationLevel.ENGINEER);
        ticket.setSlaStatus(SLAStatus.WITHIN_SLA);
        // Ten days back, which is past the widest seeded window, and to whole
        // seconds because a DATETIME column holds no fractional part.
        ticket.setCreatedDate(LocalDateTime.now().withNano(0).minusDays(10));
        ticket.setCreatedBy(PROBE_ACTOR);
        sla.stampDeadlines(ticket);
        return ticket;
    }

    /**
     * A synthetic ticket in flight, used to put the policy in a position the
     * seeded data cannot reach.
     *
     * @param firstResponseAt when somebody first answered, or null for a
     *                        ticket nobody has answered
     */
    private static TroubleTicket inFlight(LocalDateTime raisedAt, LocalDateTime firstResponseAt) {
        TroubleTicket ticket = new TroubleTicket();
        ticket.setTicketNumber("TT-TEST-000000");
        ticket.setPriority(Priority.CRITICAL);
        ticket.setStatus(TicketStatus.IN_PROGRESS);
        ticket.setSlaStatus(SLAStatus.WITHIN_SLA);
        ticket.setEscalationLevel(EscalationLevel.ENGINEER);
        ticket.setCreatedDate(raisedAt);
        ticket.setSlaResponseDeadline(raisedAt.plusMinutes(CRITICAL_BAND.getResponseMinutes()));
        ticket.setSlaDeadline(raisedAt.plusMinutes(CRITICAL_BAND.getResolutionMinutes()));
        ticket.setFirstResponseDate(firstResponseAt);
        return ticket;
    }

    private static SlaEvaluation verdict(TroubleTicket ticket, LocalDateTime at) {
        return SlaEvaluation.of(ticket, CRITICAL_BAND, CLOCK, at);
    }

    /**
     * A breached candidate in one band, for the ordering checks.
     */
    private static EscalationCandidate candidateAt(EscalationPolicy policy, String ticketNumber,
                                                   Priority priority, LocalDateTime raisedAt,
                                                   LocalDateTime at) {
        TroubleTicket ticket = inFlight(raisedAt, null);
        ticket.setTicketNumber(ticketNumber);
        ticket.setPriority(priority);
        return policy.assess(ticket, verdict(ticket, at))
                .orElseThrow(() -> new IllegalStateException(
                        ticketNumber + " should have been a candidate"));
    }

    /**
     * Drains a queue built from these candidates.
     *
     * <p>Polled rather than iterated, because a {@link PriorityQueue}'s
     * iterator promises no order at all: only {@code poll} sees the heap in
     * order. This is the same mistake the service would make if it returned
     * the queue itself.</p>
     */
    private static List<EscalationCandidate> poll(List<EscalationCandidate> offered) {
        Queue<EscalationCandidate> queue = new PriorityQueue<EscalationCandidate>(
                Math.max(offered.size(), 1), EscalationCandidate.mostUrgentFirst());
        queue.addAll(offered);
        List<EscalationCandidate> ordered = new ArrayList<EscalationCandidate>();
        while (!queue.isEmpty()) {
            ordered.add(queue.poll());
        }
        return ordered;
    }

    /* ---------- Reading back ---------- */

    private static String levelOf(TroubleTicketDAO tickets, String ticketNumber) {
        return tickets.findByTicketNumber(ticketNumber)
                .map(ticket -> String.valueOf(ticket.getEscalationLevel()))
                .orElse("(missing)");
    }

    private static String statusOf(TroubleTicketDAO tickets, String ticketNumber) {
        return tickets.findByTicketNumber(ticketNumber)
                .map(ticket -> String.valueOf(ticket.getStatus()))
                .orElse("(missing)");
    }

    private static Long idOf(TroubleTicketDAO tickets, String ticketNumber) {
        return tickets.findByTicketNumber(ticketNumber)
                .map(TroubleTicket::getId)
                .orElseThrow(() -> new IllegalStateException(ticketNumber + " is missing"));
    }

    private static Long anyEngineerId(DAOFactory factory) {
        return factory.getNetworkEngineerDAO().findAll().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("The engineer roster is empty"))
                .getId();
    }

    private static Optional<TroubleTicket> anyFinishedTicket(TroubleTicketDAO tickets) {
        return tickets.findAll().stream()
                .filter(ticket -> ticket.getStatus() != null && ticket.getStatus().isFinished())
                .findFirst();
    }

    /**
     * Puts a ticket's status and rung back to given values, which is how the
     * seeded ticket survives the stored procedure committing.
     */
    private static void restore(TroubleTicketDAO tickets, TroubleTicket ticket,
                                TicketStatus status, EscalationLevel level) {
        TroubleTicket current = tickets.getById(ticket.getId());
        current.setStatus(status);
        current.setEscalationLevel(level);
        tickets.update(current);
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
     * Who the escalation told, ignoring any earlier notice about the same
     * ticket. Sorted, because who was told matters and the order the rows
     * were written in does not.
     */
    private static String rolesTold(NotificationDAO inbox, TroubleTicket ticket) {
        List<String> roles = new ArrayList<String>();
        for (Notification sent : inbox.findByTicketId(ticket.getId())) {
            if (sent.getNotificationType() == NotificationType.TICKET_ESCALATED) {
                roles.add(sent.getRecipientRole().name());
            }
        }
        Collections.sort(roles);
        return distinct(roles).toString();
    }

    private static List<String> distinct(List<String> values) {
        return values.stream().distinct().collect(Collectors.toList());
    }

    /* ---------- Small assertions ---------- */

    private static boolean isUrgencyOrdered(List<EscalationCandidate> queue) {
        for (int index = 1; index < queue.size(); index++) {
            if (queue.get(index).getPriority().getWeight()
                    > queue.get(index - 1).getPriority().getWeight()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether each step starts on the rung the previous one ended on, which
     * is what makes the trail readable as a climb rather than as a set of
     * unrelated moves.
     */
    private static boolean isChained(List<EscalationHistory> trail) {
        for (int index = 1; index < trail.size(); index++) {
            if (trail.get(index).getFromLevel() != trail.get(index - 1).getToLevel()) {
                return false;
            }
        }
        return true;
    }

    private static int positionOf(List<EscalationCandidate> queue, String ticketNumber) {
        for (int index = 0; index < queue.size(); index++) {
            if (ticketNumber.equals(queue.get(index).getTicketNumber())) {
                return index;
            }
        }
        throw new IllegalStateException(ticketNumber + " was not in the queue");
    }

    private static int positionOfOutcome(List<EscalationOutcome> outcomes, String ticketNumber) {
        for (int index = 0; index < outcomes.size(); index++) {
            if (ticketNumber.equals(outcomes.get(index).getTicketNumber())) {
                return index;
            }
        }
        throw new IllegalStateException(ticketNumber + " was not swept");
    }

    /**
     * Whether asking the same question twice gives the same queue, which is
     * how a read only preview proves it is read only.
     */
    private static boolean sameCandidates(EscalationService service, UserSession actor) {
        return numbersOf(service.candidates(actor, 0))
                .equals(numbersOf(service.candidates(actor, 0)));
    }

    /* ---------- Rendering ---------- */

    private static String namesOf(EscalationLevel[] levels) {
        return Arrays.stream(levels)
                .map(EscalationLevel::getDisplayName)
                .collect(Collectors.joining(", "));
    }

    private static String levelsOf(EscalationLevel[] levels) {
        return Arrays.stream(levels)
                .map(level -> String.valueOf(level.getLevel()))
                .collect(Collectors.toList())
                .toString();
    }

    private static String codesOf(EscalationLevel[] levels) {
        return Arrays.stream(levels)
                .map(EscalationLevel::getCode)
                .collect(Collectors.toList())
                .toString();
    }

    private static String numbersOf(List<EscalationCandidate> candidates) {
        return candidates.stream()
                .map(EscalationCandidate::getTicketNumber)
                .collect(Collectors.joining(", "));
    }

    private static String nextOf(EscalationLevel level) {
        return level.next().map(EscalationLevel::name).orElse("(none)");
    }

    private static String nextOf(EscalationPolicy policy, EscalationLevel current,
                                 SlaEvaluation verdict) {
        return policy.nextStepFor(current, verdict).map(EscalationLevel::name).orElse("(none)");
    }

    private static String triggerOf(EscalationPolicy policy, SlaEvaluation verdict) {
        return policy.triggerFor(verdict).map(EscalationTrigger::name).orElse("(none)");
    }

    /* ---------- Shared ---------- */

    private static UserSession sessionFor(Role role, String username, Long customerId,
                                          Long engineerId) {
        UserAccount account = new UserAccount(username, "Verification Actor",
                username + "@example.test", role);
        account.setId(-1L);
        return new UserSession(account, null, customerId, engineerId);
    }

    private static int countProbes(TroubleTicketDAO tickets) {
        int found = 0;
        for (String number : PROBES) {
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
    private static int removeProbes(TroubleTicketDAO tickets) {
        int removed = 0;
        for (String number : PROBES) {
            Optional<TroubleTicket> stray = tickets.findByTicketNumber(number);
            if (stray.isPresent() && tickets.deleteById(stray.get().getId())) {
                removed++;
            }
        }
        return removed;
    }

    private static boolean canReach(Runnable work) {
        try {
            work.run();
            return true;
        } catch (RuntimeException thrown) {
            return false;
        }
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

    private static String fromLevelsOf(List<EscalationHistory> trail) {
        return trail.stream()
                .map(row -> row.getFromLevel().getDisplayName())
                .collect(Collectors.joining(", "));
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
