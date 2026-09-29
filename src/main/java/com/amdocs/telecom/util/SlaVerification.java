package com.amdocs.telecom.util;

import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.TransactionTemplate;
import com.amdocs.telecom.dao.TroubleTicketDAO;
import com.amdocs.telecom.dto.OpenTicketDTO;
import com.amdocs.telecom.dto.SlaComplianceDTO;
import com.amdocs.telecom.model.AuditLog;
import com.amdocs.telecom.model.SLAConfiguration;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.UserAccount;
import com.amdocs.telecom.model.enums.EscalationLevel;
import com.amdocs.telecom.model.enums.IncidentCategory;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.model.enums.Role;
import com.amdocs.telecom.model.enums.SLAStatus;
import com.amdocs.telecom.model.enums.Severity;
import com.amdocs.telecom.model.enums.TicketStatus;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.SlaService;
import com.amdocs.telecom.service.impl.SlaServiceImpl;
import com.amdocs.telecom.service.sla.BusinessHoursSlaClock;
import com.amdocs.telecom.service.sla.ContinuousSlaClock;
import com.amdocs.telecom.service.sla.SlaClock;
import com.amdocs.telecom.service.sla.SlaClockRegistry;
import com.amdocs.telecom.service.sla.SlaEvaluation;

import java.sql.Savepoint;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Exercises the SLA engine against the seeded database.
 *
 * <p>Two kinds of check. The clock strategies are pure arithmetic and are
 * verified against dates worked out by hand, so a wrong answer is
 * unambiguous. Everything else runs against the live tables, including a
 * ticket by ticket comparison of the Java verdict with the one
 * {@code fn_sla_status} reaches independently.</p>
 *
 * <p>Nothing is left behind. The checks that write run inside a transaction
 * that is rolled back to a savepoint, which is also how the retuning check
 * can change an SLA band and then put it back.</p>
 */
public final class SlaVerification {

    private SlaVerification() {
        throw new AssertionError("SlaVerification is not instantiable");
    }

    /** Year 9999 so a probe ticket number cannot collide with a real one. */
    private static final String JAVA_STAMPED_TICKET = "TT-9999-999001";
    private static final String TRIGGER_FILLED_TICKET = "TT-9999-999002";

    private static final String PROBE_ACTOR = "vfy-manager";

    /**
     * Clock drift allowance when comparing Java with SQL. The two verdicts
     * are reached from two readings of the clock taken moments apart, so a
     * ticket sitting on a boundary can legitimately fall either side of it.
     */
    private static final int BOUNDARY_TOLERANCE_MINUTES = 2;

    private static int checksRun;
    private static int checksFailed;
    private static int boundaryCasesTolerated;

    /**
     * Runs every check and prints a report.
     *
     * @return 0 when everything passed, 1 otherwise
     */
    public static int execute() {
        checksRun = 0;
        checksFailed = 0;
        boundaryCasesTolerated = 0;

        SlaService sla = new SlaServiceImpl();
        DAOFactory factory = DAOFactory.getInstance();

        System.out.println("  SLA engine verification");
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println();

        try {
            verifyContinuousClock();
            verifyBusinessHoursClock();
            verifyClockRegistry();
            verifyConfiguredWindows(sla);
            verifyDeadlineStamping(sla);
            verifyStatusDecision();
            verifyResponseAndAttentionRules();
            verifyLiveEvaluation(sla, factory);
            verifyAgreementWithDatabase(sla, factory);
            verifyCompliance(sla);
            verifyPersistence(sla, factory);
            verifyRetuning(sla, factory);
        } catch (RuntimeException failure) {
            checksFailed++;
            System.out.println("  Verification aborted: " + failure);
            AppLogger.error(SlaVerification.class, "SLA verification aborted", failure);
        } finally {
            // The cache may hold windows written inside a rolled back
            // transaction, so it is dropped whatever happened above.
            sla.reload();
            int strays = removeProbeTickets(factory.getTroubleTicketDAO());
            if (strays > 0) {
                System.out.println();
                System.out.println("  Removed " + strays + " stray probe ticket(s)");
            }
        }

        System.out.println();
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println("  " + (checksRun - checksFailed) + " of " + checksRun + " checks passed");
        if (boundaryCasesTolerated > 0) {
            System.out.println("  " + boundaryCasesTolerated
                    + " ticket(s) sat on an SLA boundary and were allowed clock drift");
        }
        System.out.println();

        if (checksFailed == 0) {
            System.out.println("  The SLA engine is working against the live database.");
            AppLogger.info(SlaVerification.class, "SLA verification passed " + checksRun + " checks");
        } else {
            System.out.println("  " + checksFailed + " check(s) failed. See the detail above.");
            AppLogger.warn(SlaVerification.class,
                    "SLA verification had " + checksFailed + " failure(s)");
        }
        System.out.println();
        return checksFailed == 0 ? 0 : 1;
    }

    /* ---------- 1. The round the clock strategy ---------- */

    private static void verifyContinuousClock() {
        section("1. Continuous clock");
        SlaClock clock = ContinuousSlaClock.getInstance();

        check("name", "continuous", clock.getName());
        check("counts every minute", "true", String.valueOf(clock.isContinuous()));
        check("shared instance", "true",
                String.valueOf(ContinuousSlaClock.getInstance() == clock));

        LocalDateTime friday1730 = LocalDateTime.of(2026, 9, 25, 17, 30);
        check("2 hour window from Fri 17:30", "2026-09-25T19:30",
                clock.deadlineFrom(friday1730, 120).toString());
        check("48 hour window crosses the weekend", "2026-09-27T17:30",
                clock.deadlineFrom(friday1730, 2880).toString());
        check("15 minute window", "2026-09-25T17:45",
                clock.deadlineFrom(friday1730, 15).toString());

        check("elapsed over a weekend", "2880",
                String.valueOf(clock.elapsedMinutes(friday1730, friday1730.plusDays(2))));
        check("elapsed is never negative", "0",
                String.valueOf(clock.elapsedMinutes(friday1730, friday1730.minusHours(1))));
        check("elapsed over no time", "0",
                String.valueOf(clock.elapsedMinutes(friday1730, friday1730)));

        LocalDateTime deadline = clock.deadlineFrom(friday1730, 120);
        check("half a window consumed", "0.50", String.format("%.2f",
                clock.consumedFraction(friday1730, deadline, friday1730.plusHours(1))));
        check("whole window consumed", "1.00", String.format("%.2f",
                clock.consumedFraction(friday1730, deadline, deadline)));
        check("overrun reads above one", "1.50", String.format("%.2f",
                clock.consumedFraction(friday1730, deadline, friday1730.plusHours(3))));

        check("a null moment is refused", "IllegalArgumentException",
                nameOfThrown(() -> clock.deadlineFrom(null, 120)));
        check("a negative window is refused", "IllegalArgumentException",
                nameOfThrown(() -> clock.deadlineFrom(friday1730, -1)));
    }

    /* ---------- 2. The working hours strategy ---------- */

    private static void verifyBusinessHoursClock() {
        section("2. Business hours clock (09:00-18:00, Mon-Fri)");
        BusinessHoursSlaClock clock = new BusinessHoursSlaClock(
                LocalTime.of(9, 0), LocalTime.of(18, 0),
                EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY));

        check("name", "business-hours", clock.getName());
        check("does not count every minute", "false", String.valueOf(clock.isContinuous()));
        check("minutes in a working day", "540", String.valueOf(clock.minutesPerDay()));

        // 25 September 2026 is a Friday, 28 September is the Monday after.
        LocalDateTime friday1730 = LocalDateTime.of(2026, 9, 25, 17, 30);
        check("4 hours from Fri 17:30 lands Monday", "2026-09-28T12:30",
                clock.deadlineFrom(friday1730, 240).toString());

        LocalDateTime saturday1000 = LocalDateTime.of(2026, 9, 26, 10, 0);
        check("raised on Saturday waits for Monday", "2026-09-28T10:00",
                clock.deadlineFrom(saturday1000, 60).toString());

        LocalDateTime wednesday0900 = LocalDateTime.of(2026, 9, 23, 9, 0);
        check("exactly one working day", "2026-09-23T18:00",
                clock.deadlineFrom(wednesday0900, 540).toString());
        check("one minute more rolls over", "2026-09-24T09:01",
                clock.deadlineFrom(wednesday0900, 541).toString());
        check("before opening waits for opening", "2026-09-23T10:00",
                clock.deadlineFrom(LocalDateTime.of(2026, 9, 23, 7, 0), 60).toString());
        check("after closing waits for tomorrow", "2026-09-24T10:00",
                clock.deadlineFrom(LocalDateTime.of(2026, 9, 23, 19, 0), 60).toString());

        // The LOW band's 48 hour window is why this strategy exists: 2880
        // working minutes is more than a working week.
        check("the 48 hour LOW window", "2026-09-28T12:00",
                clock.deadlineFrom(LocalDateTime.of(2026, 9, 21, 9, 0), 2880).toString());

        check("elapsed skips the weekend", "120", String.valueOf(clock.elapsedMinutes(
                LocalDateTime.of(2026, 9, 25, 17, 0), LocalDateTime.of(2026, 9, 28, 10, 0))));
        check("elapsed inside a weekend is zero", "0", String.valueOf(clock.elapsedMinutes(
                saturday1000, LocalDateTime.of(2026, 9, 27, 10, 0))));
        check("elapsed ignores the evening", "540", String.valueOf(clock.elapsedMinutes(
                wednesday0900, LocalDateTime.of(2026, 9, 23, 23, 0))));

        LocalDateTime deadline = clock.deadlineFrom(wednesday0900, 540);
        check("half a working day consumed", "0.50", String.format("%.2f",
                clock.consumedFraction(wednesday0900, deadline,
                        LocalDateTime.of(2026, 9, 23, 13, 30))));
        check("overnight consumes nothing more", "1.00", String.format("%.2f",
                clock.consumedFraction(wednesday0900, deadline,
                        LocalDateTime.of(2026, 9, 23, 22, 0))));

        check("a working day that ends before it opens is refused",
                "IllegalArgumentException",
                nameOfThrown(() -> new BusinessHoursSlaClock(LocalTime.of(18, 0),
                        LocalTime.of(9, 0), EnumSet.of(DayOfWeek.MONDAY))));
        check("a working week with no days is refused", "IllegalArgumentException",
                nameOfThrown(() -> new BusinessHoursSlaClock(LocalTime.of(9, 0),
                        LocalTime.of(18, 0), EnumSet.noneOf(DayOfWeek.class))));
    }

    /* ---------- 3. Strategy selection ---------- */

    private static void verifyClockRegistry() {
        section("3. Clock registry and factory");
        SlaClockRegistry registry = SlaClockRegistry.getInstance();

        check("singleton", "true",
                String.valueOf(SlaClockRegistry.getInstance() == registry));
        check("every band resolved", "4", String.valueOf(registry.assignments().size()));
        check("shipped default is continuous", "true",
                String.valueOf(registry.isEverythingContinuous()));

        for (Priority band : Priority.values()) {
            check("  " + band.name() + " clock", "continuous",
                    registry.clockFor(band).getName());
        }
        check("bands sharing a clock share the instance", "true",
                String.valueOf(registry.clockFor(Priority.LOW)
                        == registry.clockFor(Priority.CRITICAL)));

        check("factory builds the continuous clock", "continuous",
                SlaClockRegistry.create("continuous").getName());
        check("factory builds the business hours clock", "business-hours",
                SlaClockRegistry.create("business-hours").getName());
        check("factory ignores case and padding", "continuous",
                SlaClockRegistry.create("  CONTINUOUS  ").getName());
        check("an unknown clock fails closed", "ConfigurationException",
                nameOfThrown(() -> SlaClockRegistry.create("round-the-moon")));
        check("a missing name fails closed", "ConfigurationException",
                nameOfThrown(() -> SlaClockRegistry.create(null)));
        check("a null band is refused", "IllegalArgumentException",
                nameOfThrown(() -> registry.clockFor(null)));
    }

    /* ---------- 4. The configured windows ---------- */

    private static void verifyConfiguredWindows(SlaService sla) {
        section("4. Windows from sla_configuration");

        check("bands loaded", "4", String.valueOf(sla.configurations().size()));
        checkWindow(sla, Priority.CRITICAL, 15, 120);
        checkWindow(sla, Priority.HIGH, 30, 240);
        checkWindow(sla, Priority.MEDIUM, 120, 720);
        checkWindow(sla, Priority.LOW, 480, 2880);

        check("at risk threshold", "80",
                String.valueOf(sla.configurationFor(Priority.CRITICAL).getAtRiskThresholdPercent()));
        check("window rendered for the console", "2 hours",
                sla.configurationFor(Priority.CRITICAL).describeResolutionWindow());
        check("response window rendered", "15 min",
                sla.configurationFor(Priority.CRITICAL).describeResponseWindow());

        Map<Priority, SLAConfiguration> first = sla.configurations();
        check("cached between calls", "true", String.valueOf(first == sla.configurations()));
        sla.reload();
        Map<Priority, SLAConfiguration> reloaded = sla.configurations();
        check("reload re-reads the table", "false", String.valueOf(first == reloaded));
        check("  and finds the same windows", "120",
                String.valueOf(reloaded.get(Priority.CRITICAL).getResolutionMinutes()));

        check("no priority is refused", "ValidationException",
                nameOfThrown(() -> sla.configurationFor(null)));
        check("band table renders", "true",
                String.valueOf(sla.describeBands().contains("Critical")));
    }

    private static void checkWindow(SlaService sla, Priority band, int response, int resolution) {
        SLAConfiguration configuration = sla.configurationFor(band);
        check("  " + band.name() + " window", response + "/" + resolution,
                configuration.getResponseMinutes() + "/" + configuration.getResolutionMinutes());
    }

    /* ---------- 5. Stamping deadlines ---------- */

    private static void verifyDeadlineStamping(SlaService sla) {
        section("5. Deadlines at raise time");
        LocalDateTime raisedAt = LocalDateTime.of(2026, 9, 25, 14, 0);

        check("CRITICAL response due", "2026-09-25T14:15",
                sla.responseDeadlineFor(Priority.CRITICAL, raisedAt).toString());
        check("CRITICAL resolution due", "2026-09-25T16:00",
                sla.resolutionDeadlineFor(Priority.CRITICAL, raisedAt).toString());
        check("HIGH resolution due", "2026-09-25T18:00",
                sla.resolutionDeadlineFor(Priority.HIGH, raisedAt).toString());
        check("MEDIUM resolution due", "2026-09-26T02:00",
                sla.resolutionDeadlineFor(Priority.MEDIUM, raisedAt).toString());
        check("LOW resolution due", "2026-09-27T14:00",
                sla.resolutionDeadlineFor(Priority.LOW, raisedAt).toString());

        TroubleTicket ticket = new TroubleTicket();
        ticket.setPriority(Priority.HIGH);
        ticket.setCreatedDate(raisedAt);
        sla.stampDeadlines(ticket);
        check("stamped response deadline", "2026-09-25T14:30",
                String.valueOf(ticket.getSlaResponseDeadline()));
        check("stamped resolution deadline", "2026-09-25T18:00",
                String.valueOf(ticket.getSlaDeadline()));

        TroubleTicket undated = new TroubleTicket();
        undated.setPriority(Priority.LOW);
        sla.stampDeadlines(undated);
        check("a missing raise date is filled in", "true",
                String.valueOf(undated.getCreatedDate() != null));
        check("  and the deadline follows from it", "true",
                String.valueOf(undated.getSlaDeadline() != null
                        && undated.getSlaDeadline().equals(
                                undated.getCreatedDate().plusMinutes(2880))));

        check("a ticket with no priority is refused", "ValidationException",
                nameOfThrown(() -> sla.stampDeadlines(new TroubleTicket())));
        check("a null ticket is refused", "ValidationException",
                nameOfThrown(() -> sla.stampDeadlines(null)));
        check("a null raise date is refused", "ValidationException",
                nameOfThrown(() -> sla.resolutionDeadlineFor(Priority.LOW, null)));
    }

    /* ---------- 6. The status decision ---------- */

    private static void verifyStatusDecision() {
        section("6. Within SLA, at risk, breached");

        // A two hour window opened at noon, judged at a fixed moment so the
        // answers cannot drift with the wall clock.
        LocalDateTime raisedAt = LocalDateTime.of(2026, 9, 25, 12, 0);
        SLAConfiguration critical = new SLAConfiguration(Priority.CRITICAL, 15, 120);
        SlaClock clock = ContinuousSlaClock.getInstance();

        check("comfortably inside", "WITHIN_SLA",
                statusOf(open(raisedAt), critical, clock, raisedAt.plusMinutes(30)));
        check("one minute short of the threshold", "WITHIN_SLA",
                statusOf(open(raisedAt), critical, clock, raisedAt.plusMinutes(95)));
        check("exactly on the 80 percent threshold", "AT_RISK",
                statusOf(open(raisedAt), critical, clock, raisedAt.plusMinutes(96)));
        check("past the threshold", "AT_RISK",
                statusOf(open(raisedAt), critical, clock, raisedAt.plusMinutes(119)));
        check("exactly on the deadline", "AT_RISK",
                statusOf(open(raisedAt), critical, clock, raisedAt.plusMinutes(120)));
        check("one minute past the deadline", "BREACHED",
                statusOf(open(raisedAt), critical, clock, raisedAt.plusMinutes(121)));

        TroubleTicket inTime = open(raisedAt);
        inTime.setStatus(TicketStatus.RESOLVED);
        inTime.setResolutionDate(raisedAt.plusMinutes(90));
        check("resolved in time", "WITHIN_SLA",
                statusOf(inTime, critical, clock, raisedAt.plusDays(5)));

        TroubleTicket late = open(raisedAt);
        late.setStatus(TicketStatus.CLOSED);
        late.setResolutionDate(raisedAt.plusMinutes(200));
        check("resolved late stays breached forever", "BREACHED",
                statusOf(late, critical, clock, raisedAt.plusDays(5)));

        TroubleTicket cancelled = open(raisedAt);
        cancelled.setStatus(TicketStatus.CANCELLED);
        check("cancelled never breaches", "WITHIN_SLA",
                statusOf(cancelled, critical, clock, raisedAt.plusDays(5)));

        TroubleTicket noDeadline = open(raisedAt);
        noDeadline.setSlaDeadline(null);
        check("no deadline means no breach", "WITHIN_SLA",
                statusOf(noDeadline, critical, clock, raisedAt.plusDays(5)));

        SlaEvaluation halfway = SlaEvaluation.of(open(raisedAt), critical, clock,
                raisedAt.plusMinutes(60));
        check("window used", "50", String.format("%.0f", halfway.getConsumedPercent()));
        check("minutes remaining", "60",
                String.valueOf(halfway.findMinutesRemaining().orElse(-1L)));
        check("clock recorded on the verdict", "continuous", halfway.getClockName());

        SlaEvaluation overdue = SlaEvaluation.of(open(raisedAt), critical, clock,
                raisedAt.plusMinutes(150));
        check("minutes remaining goes negative", "-30",
                String.valueOf(overdue.findMinutesRemaining().orElse(0L)));
        check("stale against the stored column", "true", String.valueOf(overdue.isStale()));

        TroubleTicket alreadyMarked = open(raisedAt);
        alreadyMarked.setSlaStatus(SLAStatus.BREACHED);
        check("not stale once written back", "false", String.valueOf(
                SlaEvaluation.of(alreadyMarked, critical, clock, raisedAt.plusMinutes(150))
                        .isStale()));
    }

    /* ---------- 7. Response windows and who gets warned ---------- */

    private static void verifyResponseAndAttentionRules() {
        section("7. Response breach and attention rules");
        LocalDateTime raisedAt = LocalDateTime.of(2026, 9, 25, 12, 0);
        SLAConfiguration critical = new SLAConfiguration(Priority.CRITICAL, 15, 120);
        SlaClock clock = ContinuousSlaClock.getInstance();

        TroubleTicket unanswered = open(raisedAt);
        check("response window still open", "false", String.valueOf(
                SlaEvaluation.of(unanswered, critical, clock, raisedAt.plusMinutes(10))
                        .isResponseBreached()));
        check("response window missed", "true", String.valueOf(
                SlaEvaluation.of(unanswered, critical, clock, raisedAt.plusMinutes(20))
                        .isResponseBreached()));

        TroubleTicket answeredInTime = open(raisedAt);
        answeredInTime.setFirstResponseDate(raisedAt.plusMinutes(10));
        check("answered in time", "false", String.valueOf(
                SlaEvaluation.of(answeredInTime, critical, clock, raisedAt.plusDays(1))
                        .isResponseBreached()));

        TroubleTicket answeredLate = open(raisedAt);
        answeredLate.setFirstResponseDate(raisedAt.plusMinutes(40));
        check("answered late", "true", String.valueOf(
                SlaEvaluation.of(answeredLate, critical, clock, raisedAt.plusDays(1))
                        .isResponseBreached()));

        TroubleTicket cancelled = open(raisedAt);
        cancelled.setStatus(TicketStatus.CANCELLED);
        check("cancelled owes no response", "false", String.valueOf(
                SlaEvaluation.of(cancelled, critical, clock, raisedAt.plusDays(1))
                        .isResponseBreached()));

        // An at risk ticket the customer is holding up should not chase the
        // engineer, but a deadline that has actually passed always does.
        TroubleTicket waitingAtRisk = open(raisedAt);
        waitingAtRisk.setStatus(TicketStatus.PENDING_CUSTOMER);
        check("at risk while waiting on the customer", "false", String.valueOf(
                SlaEvaluation.of(waitingAtRisk, critical, clock, raisedAt.plusMinutes(100))
                        .needsAttention()));
        check("breached while waiting on the customer", "true", String.valueOf(
                SlaEvaluation.of(waitingAtRisk, critical, clock, raisedAt.plusMinutes(130))
                        .needsAttention()));

        TroubleTicket inProgress = open(raisedAt);
        inProgress.setStatus(TicketStatus.IN_PROGRESS);
        check("at risk and being worked on", "true", String.valueOf(
                SlaEvaluation.of(inProgress, critical, clock, raisedAt.plusMinutes(100))
                        .needsAttention()));

        TroubleTicket resolvedLate = open(raisedAt);
        resolvedLate.setStatus(TicketStatus.RESOLVED);
        resolvedLate.setResolutionDate(raisedAt.plusMinutes(200));
        SlaEvaluation finished = SlaEvaluation.of(resolvedLate, critical, clock,
                raisedAt.plusDays(2));
        check("a finished breach needs no chasing", "false",
                String.valueOf(finished.needsAttention()));
        check("  but is still reported as breached", "true",
                String.valueOf(finished.isBreached()));
        check("  and consumption stops at resolution", "167",
                String.format("%.0f", finished.getConsumedPercent()));
    }

    /* ---------- 8. Against the live tables ---------- */

    private static void verifyLiveEvaluation(SlaService sla, DAOFactory factory) {
        section("8. Evaluating the seeded tickets");

        int openTickets = factory.getTroubleTicketDAO().findOpen().size();
        List<SlaEvaluation> evaluated = sla.evaluateOpen();
        check("one verdict per open ticket", String.valueOf(openTickets),
                String.valueOf(evaluated.size()));
        check("every verdict has a status", "true", String.valueOf(
                evaluated.stream().allMatch(evaluation -> evaluation.getLiveStatus() != null)));
        check("every verdict names its clock", "true", String.valueOf(
                evaluated.stream().allMatch(evaluation -> "continuous"
                        .equals(evaluation.getClockName()))));

        List<SlaEvaluation> breached = sla.breached();
        check("breached list is all breaches", "true",
                String.valueOf(breached.stream().allMatch(SlaEvaluation::isBreached)));
        check("  and matches a count over the same set", String.valueOf(breached.size()),
                String.valueOf(evaluated.stream().filter(SlaEvaluation::isBreached).count()));
        check("  ordered most overdue first", "true", String.valueOf(isByDeadline(breached)));

        List<SlaEvaluation> atRisk = sla.atRisk();
        check("at risk list is all at risk", "true",
                String.valueOf(atRisk.stream().allMatch(SlaEvaluation::isAtRisk)));
        check("  and disjoint from the breaches", "0", String.valueOf(
                atRisk.stream().filter(SlaEvaluation::isBreached).count()));

        long withDeadline = evaluated.stream()
                .filter(evaluation -> evaluation.getResolutionDeadline() != null)
                .count();
        List<SlaEvaluation> due = sla.dueWithin(60 * 24 * 365 * 10);
        check("every ticket with a deadline is due within ten years",
                String.valueOf(withDeadline), String.valueOf(due.size()));
        check("  ordered soonest first", "true", String.valueOf(isByDeadline(due)));
        check("a window of zero minutes finds only the overdue", "true", String.valueOf(
                sla.dueWithin(0).stream().allMatch(
                        evaluation -> evaluation.findMinutesRemaining().orElse(1L) <= 0L)));
        check("a negative window is refused", "ValidationException",
                nameOfThrown(() -> sla.dueWithin(-5)));

        List<SlaEvaluation> attention = sla.needingAttention();
        check("attention list holds only open tickets", "true",
                String.valueOf(attention.stream().allMatch(SlaEvaluation::isOpen)));
        check("  every breach on it is open", "true", String.valueOf(
                attention.stream().allMatch(SlaEvaluation::needsAttention)));

        Optional<TroubleTicket> anyOpen = factory.getTroubleTicketDAO().findOpen()
                .stream().findFirst();
        if (anyOpen.isPresent()) {
            String number = anyOpen.get().getTicketNumber();
            check("lookup by ticket number", number,
                    sla.evaluateByTicketNumber(number).getTicketNumber());
            check("  detail block renders", "true", String.valueOf(
                    sla.evaluateByTicketNumber(number).toDetailBlock().contains("SLA status")));
        }
        check("an unknown ticket number is refused", "ResourceNotFoundException",
                nameOfThrown(() -> sla.evaluateByTicketNumber("TT-0000-000000")));
    }

    /* ---------- 9. Java against the stored function ---------- */

    /**
     * The whole point of duplicating the SLA arithmetic is that both copies
     * agree. Compared ticket by ticket rather than by totals, so two
     * offsetting errors cannot cancel out.
     */
    private static void verifyAgreementWithDatabase(SlaService sla, DAOFactory factory) {
        section("9. Agreement with fn_sla_status");

        Map<String, SlaEvaluation> javaVerdicts = new HashMap<String, SlaEvaluation>();
        for (SlaEvaluation evaluation : sla.evaluateOpen()) {
            javaVerdicts.put(evaluation.getTicketNumber(), evaluation);
        }

        List<OpenTicketDTO> fromView = factory.getReportDAO().findOpenTickets();
        check("the view sees the same tickets", String.valueOf(javaVerdicts.size()),
                String.valueOf(fromView.size()));

        int statusMatches = 0;
        int minutesMatches = 0;
        int compared = 0;
        for (OpenTicketDTO row : fromView) {
            SlaEvaluation javaVerdict = javaVerdicts.get(row.getTicketNumber());
            if (javaVerdict == null) {
                continue;
            }
            compared++;

            if (javaVerdict.getLiveStatus() == row.getSlaStatus()) {
                statusMatches++;
            } else if (nearABoundary(javaVerdict, sla.configurationFor(javaVerdict.getPriority()))) {
                // Legitimately ambiguous: the two readings of the clock fall
                // either side of a threshold.
                statusMatches++;
                boundaryCasesTolerated++;
            } else {
                System.out.println("      " + row.getTicketNumber() + " java="
                        + javaVerdict.getLiveStatus() + " sql=" + row.getSlaStatus()
                        + " used=" + String.format("%.1f%%", javaVerdict.getConsumedPercent()));
            }

            Long javaMinutes = javaVerdict.findMinutesRemaining().orElse(null);
            Long sqlMinutes = row.getMinutesRemaining();
            if (javaMinutes == null ? sqlMinutes == null : sqlMinutes != null
                    && Math.abs(javaMinutes - sqlMinutes) <= 1L) {
                minutesMatches++;
            }
        }

        check("tickets compared", String.valueOf(fromView.size()), String.valueOf(compared));
        check("SLA status agrees on every ticket", String.valueOf(compared),
                String.valueOf(statusMatches));
        check("minutes remaining agrees within a minute", String.valueOf(compared),
                String.valueOf(minutesMatches));

        check("the view exposes the stored deadline", "true", String.valueOf(
                fromView.stream().allMatch(row -> {
                    SlaEvaluation verdict = javaVerdicts.get(row.getTicketNumber());
                    return verdict != null && row.getSlaDeadline() != null
                            && row.getSlaDeadline().equals(verdict.getResolutionDeadline());
                })));

        // The seed inserts tickets without deadlines, so every stored
        // deadline was computed by fn_sla_deadline inside the insert
        // trigger. Recomputing them in Java and comparing is therefore a
        // genuine test of one implementation against the other.
        int deadlinesAgreeing = 0;
        int deadlinesChecked = 0;
        for (TroubleTicket ticket : factory.getTroubleTicketDAO().findOpen()) {
            if (ticket.getSlaDeadline() == null || ticket.getCreatedDate() == null) {
                continue;
            }
            deadlinesChecked++;
            LocalDateTime computedInJava = sla.resolutionDeadlineFor(
                    ticket.getPriority(), ticket.getCreatedDate());
            if (computedInJava.equals(ticket.getSlaDeadline())) {
                deadlinesAgreeing++;
            } else {
                System.out.println("      " + ticket.getTicketNumber() + " java="
                        + computedInJava + " trigger=" + ticket.getSlaDeadline());
            }
        }
        check("deadlines checked", "true", String.valueOf(deadlinesChecked > 0));
        check("Java agrees with fn_sla_deadline on every ticket",
                String.valueOf(deadlinesChecked), String.valueOf(deadlinesAgreeing));
    }

    private static boolean nearABoundary(SlaEvaluation evaluation, SLAConfiguration window) {
        Long remaining = evaluation.findMinutesRemaining().orElse(null);
        if (remaining != null && Math.abs(remaining) <= BOUNDARY_TOLERANCE_MINUTES) {
            return true;
        }
        int resolutionMinutes = window.getResolutionMinutes();
        double percentPerMinute = resolutionMinutes <= 0 ? 100.0d : 100.0d / resolutionMinutes;
        double distance = Math.abs(evaluation.getConsumedPercent()
                - window.getAtRiskThresholdPercent());
        return distance <= BOUNDARY_TOLERANCE_MINUTES * percentPerMinute;
    }

    /* ---------- 10. Compliance figures ---------- */

    private static void verifyCompliance(SlaService sla) {
        section("10. Compliance from vw_sla_compliance");
        List<SlaComplianceDTO> compliance = sla.compliance();

        check("rows returned", "true", String.valueOf(!compliance.isEmpty()));
        check("every row names a band", "true", String.valueOf(
                compliance.stream().allMatch(row -> row.getPriority() != null)));
        check("met and breached never exceed completed", "true", String.valueOf(
                compliance.stream().allMatch(
                        row -> row.getMetSla() + row.getBreachedSla() <= row.getCompletedTickets())));
        check("completed never exceeds total", "true", String.valueOf(
                compliance.stream().allMatch(
                        row -> row.getCompletedTickets() <= row.getTotalTickets())));
        check("the windows match the configuration", "true", String.valueOf(
                compliance.stream().allMatch(row -> row.getResolutionMinutes()
                        == sla.configurationFor(row.getPriority()).getResolutionMinutes())));
    }

    /* ---------- 11. Writing back ---------- */

    /**
     * Inserts two probe tickets, one with deadlines computed in Java and one
     * with none, to show that the trigger fills only what is missing. Rolled
     * back to a savepoint, so the ticket table is untouched.
     */
    private static void verifyPersistence(SlaService sla, DAOFactory factory) {
        section("11. Persisting deadlines and status");
        TroubleTicketDAO tickets = factory.getTroubleTicketDAO();

        Optional<TroubleTicket> template = tickets.findAll().stream().findFirst();
        if (!template.isPresent()) {
            check("a seeded ticket to copy keys from", "true", "false");
            return;
        }

        TransactionTemplate.run(context -> {
            Savepoint marker = context.savepoint("before_sla_probe");
            try {
                // Seconds only. A DATETIME column holds no fractional part
                // and MySQL rounds what it is given, so a raise date with
                // nanoseconds would not read back as it was written.
                LocalDateTime raisedAt = LocalDateTime.now().withNano(0).minusHours(3);

                TroubleTicket stamped = probeTicket(template.get(), JAVA_STAMPED_TICKET, raisedAt);
                sla.stampDeadlines(stamped);
                LocalDateTime expectedDeadline = stamped.getSlaDeadline();
                Long stampedId = tickets.insert(stamped).getId();

                TroubleTicket stored = tickets.getById(stampedId);
                check("Java deadline survives the insert", String.valueOf(expectedDeadline),
                        String.valueOf(stored.getSlaDeadline()));
                check("  the trigger did not overwrite it", "true", String.valueOf(
                        stored.getSlaDeadline().equals(raisedAt.plusMinutes(120))));
                check("  response deadline stored too", "true", String.valueOf(
                        stored.getSlaResponseDeadline().equals(raisedAt.plusMinutes(15))));

                TroubleTicket bare = probeTicket(template.get(), TRIGGER_FILLED_TICKET, raisedAt);
                Long bareId = tickets.insert(bare).getId();
                TroubleTicket filled = tickets.getById(bareId);
                check("the trigger fills a missing deadline", "true",
                        String.valueOf(filled.getSlaDeadline() != null));
                check("  at the round the clock window", "true", String.valueOf(
                        filled.getSlaDeadline().equals(raisedAt.plusMinutes(120))));

                SlaEvaluation verdict = sla.evaluate(stored);
                check("a three hour old CRITICAL ticket", "BREACHED",
                        String.valueOf(verdict.getLiveStatus()));
                check("  stored column is behind", "true", String.valueOf(verdict.isStale()));

                int corrected = sla.refreshStoredStatuses();
                check("the monitor corrected rows", "true", String.valueOf(corrected >= 1));
                check("  the probe now reads breached", "BREACHED",
                        String.valueOf(tickets.getById(stampedId).getSlaStatus()));
                check("  a second pass has nothing to do", "0",
                        String.valueOf(sla.refreshStoredStatuses()));
                check("  writing the same status changes nothing", "false", String.valueOf(
                        tickets.updateSlaStatus(stampedId, SLAStatus.BREACHED)));

                tickets.updatePriority(stampedId, Priority.LOW);
                check("recalculating after a priority change", "true",
                        String.valueOf(sla.recalculateDeadlines(stampedId)));
                check("  the LOW window now applies", "true", String.valueOf(
                        tickets.getById(stampedId).getSlaDeadline()
                                .equals(raisedAt.plusMinutes(2880))));
                check("  and it is no longer breached", "WITHIN_SLA",
                        String.valueOf(sla.evaluate(tickets.getById(stampedId)).getLiveStatus()));

                tickets.updateStatus(stampedId, TicketStatus.OPEN, TicketStatus.CANCELLED);
                check("a finished ticket keeps its deadlines", "false",
                        String.valueOf(sla.recalculateDeadlines(stampedId)));
                check("an unknown ticket is refused", "ResourceNotFoundException",
                        nameOfThrown(() -> sla.recalculateDeadlines(-999L)));
            } finally {
                context.rollbackTo(marker);
            }
        });

        check("nothing was left in the ticket table", "0",
                String.valueOf(countProbeTickets(tickets)));
    }

    /* ---------- 12. Retuning a band ---------- */

    private static void verifyRetuning(SlaService sla, DAOFactory factory) {
        section("12. Retuning a band");
        UserSession manager = sessionFor(Role.NETWORK_MANAGER);
        UserSession customer = sessionFor(Role.CUSTOMER);

        check("a customer may not retune", "AuthorizationException",
                nameOfThrown(() -> sla.retuneWindows(customer, Priority.CRITICAL, 20, 180)));
        check("nobody signed in may not retune", "AuthenticationException",
                nameOfThrown(() -> sla.retuneWindows(null, Priority.CRITICAL, 20, 180)));
        check("a zero window is refused", "ValidationException",
                nameOfThrown(() -> sla.retuneWindows(manager, Priority.CRITICAL, 0, 180)));
        check("a negative window is refused", "ValidationException",
                nameOfThrown(() -> sla.retuneWindows(manager, Priority.CRITICAL, 20, -1)));
        check("resolution before response is refused", "ValidationException",
                nameOfThrown(() -> sla.retuneWindows(manager, Priority.CRITICAL, 180, 20)));
        check("an absurd window is refused", "ValidationException",
                nameOfThrown(() -> sla.retuneWindows(manager, Priority.CRITICAL, 20, 999999)));
        check("no band is refused", "ValidationException",
                nameOfThrown(() -> sla.retuneWindows(manager, null, 20, 180)));
        check("the windows already in force are refused", "ValidationException",
                nameOfThrown(() -> sla.retuneWindows(manager, Priority.CRITICAL, 15, 120)));

        TransactionTemplate.run(context -> {
            Savepoint marker = context.savepoint("before_sla_retune");
            try {
                sla.retuneWindows(manager, Priority.CRITICAL, 20, 180);
                check("the change applied", "20/180",
                        sla.configurationFor(Priority.CRITICAL).getResponseMinutes() + "/"
                                + sla.configurationFor(Priority.CRITICAL).getResolutionMinutes());
                check("  new tickets get the new window", "2026-09-25T17:00",
                        sla.resolutionDeadlineFor(Priority.CRITICAL,
                                LocalDateTime.of(2026, 9, 25, 14, 0)).toString());

                Optional<AuditLog> entry = factory.getAuditLogDAO()
                        .findByUser(PROBE_ACTOR, 5).stream()
                        .filter(row -> "SLA_WINDOWS_CHANGED".equals(row.getAction()))
                        .findFirst();
                check("  an audit row was written", "true", String.valueOf(entry.isPresent()));
                check("  recording the change", "15/120 -> 20/180",
                        entry.map(row -> row.getOldValue() + " -> " + row.getNewValue())
                                .orElse("(missing)"));
            } finally {
                context.rollbackTo(marker);
            }
        });

        // The cache still describes the rolled back windows until told
        // otherwise, which is exactly why reload exists.
        sla.reload();
        check("the rollback restored the band", "15/120",
                sla.configurationFor(Priority.CRITICAL).getResponseMinutes() + "/"
                        + sla.configurationFor(Priority.CRITICAL).getResolutionMinutes());
    }

    /* ---------- Helpers ---------- */

    private static TroubleTicket open(LocalDateTime raisedAt) {
        TroubleTicket ticket = new TroubleTicket();
        ticket.setTicketNumber("TT-TEST-000000");
        ticket.setPriority(Priority.CRITICAL);
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setSlaStatus(SLAStatus.WITHIN_SLA);
        ticket.setCreatedDate(raisedAt);
        ticket.setSlaResponseDeadline(raisedAt.plusMinutes(15));
        ticket.setSlaDeadline(raisedAt.plusMinutes(120));
        return ticket;
    }

    private static String statusOf(TroubleTicket ticket, SLAConfiguration window,
                                   SlaClock clock, LocalDateTime at) {
        return String.valueOf(SlaEvaluation.of(ticket, window, clock, at).getLiveStatus());
    }

    /**
     * A CRITICAL probe ticket borrowing the customer and service keys of a
     * seeded one, so the foreign keys are certain to be valid.
     */
    private static TroubleTicket probeTicket(TroubleTicket template, String ticketNumber,
                                             LocalDateTime raisedAt) {
        TroubleTicket ticket = new TroubleTicket();
        ticket.setTicketNumber(ticketNumber);
        ticket.setCustomerId(template.getCustomerId());
        ticket.setServiceId(template.getServiceId());
        ticket.setCategory(IncidentCategory.OTHER);
        ticket.setDescription("SLA engine verification probe");
        ticket.setPriority(Priority.CRITICAL);
        ticket.setSeverity(Severity.MAJOR);
        ticket.setStatus(TicketStatus.OPEN);
        ticket.setEscalationLevel(EscalationLevel.ENGINEER);
        // Deliberately behind the truth, so the monitor has something to fix.
        ticket.setSlaStatus(SLAStatus.WITHIN_SLA);
        ticket.setCreatedDate(raisedAt);
        ticket.setCreatedBy(PROBE_ACTOR);
        return ticket;
    }

    private static UserSession sessionFor(Role role) {
        UserAccount account = new UserAccount(PROBE_ACTOR, "Verification Actor",
                PROBE_ACTOR + "@example.test", role);
        account.setId(-1L);
        return new UserSession(account, null, null, null);
    }

    private static boolean isByDeadline(List<SlaEvaluation> evaluations) {
        for (int index = 1; index < evaluations.size(); index++) {
            LocalDateTime earlier = evaluations.get(index - 1).getResolutionDeadline();
            LocalDateTime later = evaluations.get(index).getResolutionDeadline();
            if (earlier != null && later != null && earlier.isAfter(later)) {
                return false;
            }
        }
        return true;
    }

    private static int countProbeTickets(TroubleTicketDAO tickets) {
        int found = 0;
        if (tickets.findByTicketNumber(JAVA_STAMPED_TICKET).isPresent()) {
            found++;
        }
        if (tickets.findByTicketNumber(TRIGGER_FILLED_TICKET).isPresent()) {
            found++;
        }
        return found;
    }

    /**
     * Last resort cleanup. The rollback should have dealt with the probes, so
     * anything here means a check threw outside the transaction.
     */
    private static int removeProbeTickets(TroubleTicketDAO tickets) {
        int removed = 0;
        for (String number : new String[]{JAVA_STAMPED_TICKET, TRIGGER_FILLED_TICKET}) {
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
        report(label, expected, actual, expected.equals(actual));
    }

    private static void report(String label, String expected, String actual, boolean passed) {
        checksRun++;
        if (!passed) {
            checksFailed++;
        }
        System.out.println(String.format("    [%s] %-46s expected=%-22s actual=%s",
                passed ? "PASS" : "FAIL", label, expected, actual));
    }
}
