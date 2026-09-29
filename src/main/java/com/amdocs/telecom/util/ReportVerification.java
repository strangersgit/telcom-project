package com.amdocs.telecom.util;

import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.ReportDAO;
import com.amdocs.telecom.dto.CategoryIncidentDTO;
import com.amdocs.telecom.dto.EngineerWorkloadDTO;
import com.amdocs.telecom.dto.RepeatIncidentDTO;
import com.amdocs.telecom.dto.SlaComplianceDTO;
import com.amdocs.telecom.model.Customer;
import com.amdocs.telecom.model.NetworkEngineer;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.enums.CustomerStatus;
import com.amdocs.telecom.model.enums.CustomerType;
import com.amdocs.telecom.model.enums.EngineerAvailability;
import com.amdocs.telecom.model.enums.IncidentCategory;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.model.enums.SLAStatus;
import com.amdocs.telecom.model.enums.Severity;
import com.amdocs.telecom.model.enums.Specialization;
import com.amdocs.telecom.model.enums.TicketStatus;
import com.amdocs.telecom.report.ReportExporter;
import com.amdocs.telecom.report.ReportFormat;
import com.amdocs.telecom.report.ReportKind;
import com.amdocs.telecom.report.ReportTable;
import com.amdocs.telecom.report.ReportWriter;
import com.amdocs.telecom.service.ReportService;
import com.amdocs.telecom.service.analytics.EngineerScorecard;
import com.amdocs.telecom.service.analytics.RepeatCustomer;
import com.amdocs.telecom.service.analytics.SlaOutcome;
import com.amdocs.telecom.service.analytics.Tally;
import com.amdocs.telecom.service.analytics.TicketAnalytics;
import com.amdocs.telecom.service.impl.ReportServiceImpl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/**
 * Exercises the Stream analytics of section 16 and the reports of
 * section 18.
 *
 * <h3>Three kinds of check, in order</h3>
 *
 * <p>First the nine analyses, against a fixture built in memory. Seven
 * tickets whose every answer can be worked out by hand, which is the only
 * way to assert that an average really is 9.33 rather than merely that it
 * is the same number it was last time. A seeded database cannot do this:
 * its contents drift as other harnesses run, and a check that says
 * "whatever the database contains" tests nothing.</p>
 *
 * <p>Then the same analyses against the live database, compared with the
 * SQL views that answer the same questions. Neither side is the oracle;
 * the point is that two independent implementations of "SLA compliance",
 * one in MySQL and one in the Stream API, land on the same numbers. When
 * they agree both are probably right, and when they drift apart this is
 * what notices.</p>
 *
 * <p>Then the reports themselves: that each of the seven builds, that the
 * table model refuses malformed input, that the CSV quoting survives the
 * values that break naive writers, and that an export reaches the disk
 * and reads back as what went in.</p>
 *
 * <h3>What it writes</h3>
 *
 * <p>Nothing to the database: every analysis and every report is a read.
 * Files are written, to a verification subdirectory of the configured
 * report directory, and deleted afterwards.</p>
 */
public final class ReportVerification {

    private ReportVerification() {
        throw new AssertionError("ReportVerification is not instantiable");
    }

    /** Exports go here rather than beside real reports, and are removed. */
    private static final String EXPORT_DIRECTORY = "reports/verification";

    /**
     * Two averages computed by two engines can differ in the last place
     * without either being wrong. A penny either way on a figure in hours
     * is not a disagreement worth failing over; anything larger is.
     */
    private static final double TOLERANCE = 0.011d;

    /** The fixture's anchor, fixed so every expected number is stable. */
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 3, 1, 9, 0);

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
        ReportExporter exporter = new ReportExporter(EXPORT_DIRECTORY);
        List<Path> written = new ArrayList<Path>();

        System.out.println("  Analytics and report verification");
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println();

        try {
            TicketAnalytics fixture = fixture();
            verifyCounts(fixture);
            verifyEngineers(fixture);
            verifySlaAnalysis(fixture);
            verifyResolutionTimes(fixture);
            verifyRepeatsAndExample(fixture);
            verifyAgainstSql(factory);
            verifyTableModel();
            verifyCsv();
            verifyText();
            verifyTheSevenReports(factory);
            verifyExport(factory, exporter, written);
        } catch (RuntimeException failure) {
            checksFailed++;
            System.out.println("  Verification aborted: " + failure);
            AppLogger.error(ReportVerification.class, "Report verification aborted", failure);
        } finally {
            int removed = cleanUp(written, exporter.getDirectory());
            if (removed > 0) {
                System.out.println();
                System.out.println("  Removed " + removed + " exported file(s)");
            }
        }

        System.out.println();
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println("  " + (checksRun - checksFailed) + " of " + checksRun
                + " checks passed");
        System.out.println();

        if (checksFailed == 0) {
            System.out.println("  The analytics agree with the database and the reports "
                    + "export cleanly.");
            AppLogger.info(ReportVerification.class,
                    "Report verification passed " + checksRun + " checks");
        } else {
            System.out.println("  " + checksFailed + " check(s) failed. See the detail above.");
            AppLogger.warn(ReportVerification.class,
                    "Report verification had " + checksFailed + " failure(s)");
        }
        System.out.println();
        return checksFailed == 0 ? 0 : 1;
    }

    /* ---------- 1. Counting, against a fixture ---------- */

    private static void verifyCounts(TicketAnalytics analytics) {
        section("1. Tickets by status, priority, region and category");

        check("the fixture holds seven tickets", "7", String.valueOf(analytics.size()));

        List<Tally<TicketStatus>> byStatus = analytics.byStatus();
        check("every status is listed, empty ones included", "8",
                String.valueOf(byStatus.size()));
        check("  in the order the document declares them",
                "Open, Assigned, In Progress, Pending Customer, Escalated, Resolved, "
                        + "Closed, Cancelled", labels(byStatus));
        check("  one open", "1", countOf(byStatus, TicketStatus.OPEN));
        check("  none merely assigned", "0", countOf(byStatus, TicketStatus.ASSIGNED));
        check("  one in progress", "1", countOf(byStatus, TicketStatus.IN_PROGRESS));
        check("  one escalated", "1", countOf(byStatus, TicketStatus.ESCALATED));
        check("  two resolved", "2", countOf(byStatus, TicketStatus.RESOLVED));
        check("  one closed", "1", countOf(byStatus, TicketStatus.CLOSED));
        check("  one cancelled", "1", countOf(byStatus, TicketStatus.CANCELLED));
        check("  and they add up", "7", String.valueOf(total(byStatus)));

        List<Tally<Priority>> byPriority = analytics.byPriority();
        check("every priority band is listed", "4", String.valueOf(byPriority.size()));
        check("  three critical", "3", countOf(byPriority, Priority.CRITICAL));
        check("  two high", "2", countOf(byPriority, Priority.HIGH));
        check("  one medium", "1", countOf(byPriority, Priority.MEDIUM));
        check("  one low", "1", countOf(byPriority, Priority.LOW));
        check("  critical is 42.86% of the whole", "42.86",
                String.valueOf(shareOf(byPriority, Priority.CRITICAL)));

        // Region comes from the customer, which is the only link the schema
        // offers between a ticket and a place.
        List<Tally<Region>> byRegion = analytics.byRegion();
        check("every region is listed", "5", String.valueOf(byRegion.size()));
        check("  five tickets in the north", "5", countOf(byRegion, Region.NORTH));
        check("  two in the south", "2", countOf(byRegion, Region.SOUTH));
        check("  none in the west", "0", countOf(byRegion, Region.WEST));
        check("  and every ticket found a region", "0",
                String.valueOf(analytics.ticketsWithoutRegion()));

        List<Tally<IncidentCategory>> byCategory = analytics.byCategory();
        check("categories come back busiest first", "Network Outage",
                byCategory.get(0).getLabel());
        check("  three network outages", "3",
                countOf(byCategory, IncidentCategory.NETWORK_OUTAGE));
        check("  two call drops", "2", countOf(byCategory, IncidentCategory.CALL_DROP));

        List<Tally<IncidentCategory>> top = analytics.topCategories(3);
        check("the worst three are named", "Network Outage, Call Drop, Broadband",
                labels(top));
        check("  empty categories are left out of the ranking", "true",
                String.valueOf(analytics.topCategories(0).size() == 4));
        check("  and asking for more than there are is not an error", "4",
                String.valueOf(analytics.topCategories(99).size()));
    }

    /* ---------- 2. Engineers ---------- */

    private static void verifyEngineers(TicketAnalytics analytics) {
        section("2. Engineer workload and performance");

        List<EngineerScorecard> workload = analytics.engineerWorkload();
        check("every engineer appears, even the idle ones", "5",
                String.valueOf(workload.size()));
        check("  busiest first", "ENG002, ENG003, ENG001, ENG004, ENG005", codes(workload));

        EngineerScorecard busiest = workload.get(0);
        check("the busiest holds two open tickets", "2", String.valueOf(busiest.getOpen()));
        check("  and has finished nothing", "0", String.valueOf(busiest.getResolved()));
        check("  so there is no average to report", "n/a",
                busiest.findAverageResolutionHours().map(String::valueOf).orElse("n/a"));
        check("  and no on time figure either", "n/a",
                busiest.findOnTimePercent().map(String::valueOf).orElse("n/a"));

        List<EngineerScorecard> performance = analytics.engineerPerformance();
        check("the same rows, ordered by what was finished",
                "ENG001, ENG002, ENG003, ENG004, ENG005", codes(performance));

        EngineerScorecard best = performance.get(0);
        check("the most productive resolved three", "3", String.valueOf(best.getResolved()));
        check("  was assigned three", "3", String.valueOf(best.getAssigned()));
        check("  holds nothing now", "0", String.valueOf(best.getOpen()));
        check("  was late once", "1", String.valueOf(best.getBreaches()));
        check("  which is 66.67% on time", "66.67",
                String.valueOf(best.findOnTimePercent().orElse(null)));
        check("  averaging 9.33 hours", "9.33",
                String.valueOf(best.findAverageResolutionHours().orElse(null)));

        EngineerScorecard idle = performance.get(performance.size() - 1);
        check("an engineer who has never been assigned anything still shows", "ENG005",
                idle.getEmployeeCode());
        check("  with nothing against them", "0", String.valueOf(idle.getAssigned()));
    }

    /* ---------- 3. SLA breach analysis ---------- */

    private static void verifySlaAnalysis(TicketAnalytics analytics) {
        section("3. SLA breach analysis");

        Map<Priority, SlaOutcome> outcomes = byPriority(analytics.slaBreachAnalysis());
        check("every band is analysed", "4", String.valueOf(outcomes.size()));

        SlaOutcome critical = outcomes.get(Priority.CRITICAL);
        check("three critical tickets were raised", "3", String.valueOf(critical.getTotal()));
        check("  two of them are finished", "2", String.valueOf(critical.getCompleted()));
        check("  one was resolved in time", "1", String.valueOf(critical.getMet()));
        check("  one was resolved late", "1", String.valueOf(critical.getBreached()));
        check("  and one is neither, being cancelled", "1",
                String.valueOf(critical.getStillRunning()));
        check("  so compliance is 50%", "50.0",
                String.valueOf(critical.findCompliancePercent().orElse(null)));
        check("  averaging 2.0 hours", "2.0",
                String.valueOf(critical.findAverageResolutionHours().orElse(null)));

        SlaOutcome high = outcomes.get(Priority.HIGH);
        check("both high tickets are still running", "2",
                String.valueOf(high.getStillRunning()));
        // The distinction the report note spells out: a ticket that is
        // heading for a breach has not breached.
        check("  neither counts as breached yet", "0", String.valueOf(high.getBreached()));
        check("  and there is no compliance figure for them", "n/a",
                high.findCompliancePercent().map(String::valueOf).orElse("n/a"));

        SlaOutcome low = outcomes.get(Priority.LOW);
        check("the low band was met in full", "100.0",
                String.valueOf(low.findCompliancePercent().orElse(null)));

        List<Tally<SLAStatus>> open = analytics.openBySlaStatus();
        check("open tickets are counted by SLA standing", "3", String.valueOf(open.size()));
        check("  three tickets are open in total", "3", String.valueOf(total(open)));
    }

    /* ---------- 4. Resolution times ---------- */

    private static void verifyResolutionTimes(TicketAnalytics analytics) {
        section("4. Average resolution time");

        OptionalDouble overall = analytics.averageResolutionHours();
        check("three tickets have a resolution time", "true",
                String.valueOf(overall.isPresent()));
        check("  averaging 9.33 hours over 1, 3 and 24", "9.33",
                String.valueOf(round2(overall.getAsDouble())));

        Map<Priority, Double> byPriority = analytics.averageResolutionHoursByPriority();
        check("critical averages two hours", "2.0",
                String.valueOf(byPriority.get(Priority.CRITICAL)));
        check("  low averages twenty four", "24.0",
                String.valueOf(byPriority.get(Priority.LOW)));
        check("  and a band with nothing resolved has no average", "true",
                String.valueOf(!byPriority.containsKey(Priority.HIGH)));

        Map<IncidentCategory, Double> byCategory = analytics.averageResolutionHoursByCategory();
        check("categories come back slowest first", "Broadband",
                byCategory.keySet().iterator().next().getDisplayName());
        check("  broadband takes twenty four hours", "24.0",
                String.valueOf(byCategory.get(IncidentCategory.BROADBAND)));
        check("  outages take two", "2.0",
                String.valueOf(byCategory.get(IncidentCategory.NETWORK_OUTAGE)));

        // An empty analysis must say "no answer", not "zero hours".
        TicketAnalytics nothing = TicketAnalytics.over(Collections.<TroubleTicket>emptyList(),
                Collections.<Customer>emptyList(), Collections.<NetworkEngineer>emptyList());
        check("an empty set has no average rather than zero", "false",
                String.valueOf(nothing.averageResolutionHours().isPresent()));
        check("  and reports nothing rather than failing", "0",
                String.valueOf(nothing.byStatus().stream()
                        .mapToLong(Tally::getCount).sum()));

        List<Tally<LocalDate>> raised = analytics.volumeByDay(BASE.toLocalDate(),
                BASE.toLocalDate().plusDays(2));
        check("a three day window gives three rows", "3", String.valueOf(raised.size()));
        check("  five tickets on the first day", "5",
                String.valueOf(raised.get(0).getCount()));
        check("  two on the second", "2", String.valueOf(raised.get(1).getCount()));
        check("  and a quiet third day shows zero rather than vanishing", "0",
                String.valueOf(raised.get(2).getCount()));

        List<Tally<LocalDate>> resolved = analytics.resolvedByDay(BASE.toLocalDate(),
                BASE.toLocalDate().plusDays(2));
        check("two were resolved on the first day", "2",
                String.valueOf(resolved.get(0).getCount()));
        check("  none on the second", "0", String.valueOf(resolved.get(1).getCount()));
        check("  and one on the third", "1", String.valueOf(resolved.get(2).getCount()));
        check("a window that ends before it starts gives nothing", "0",
                String.valueOf(analytics.volumeByDay(BASE.toLocalDate(),
                        BASE.toLocalDate().minusDays(1)).size()));
    }

    /* ---------- 5. Repeat customers and the document's example ---------- */

    private static void verifyRepeatsAndExample(TicketAnalytics analytics) {
        section("5. Repeat customers, and the example from section 16");

        List<RepeatCustomer> repeats = analytics.repeatIncidents(2);
        check("three customers have been back", "3", String.valueOf(repeats.size()));
        check("  most incidents first, then most recent", "CUST001, CUST003, CUST002",
                numbers(repeats));

        RepeatCustomer worst = repeats.get(0);
        check("the worst raised three tickets", "3",
                String.valueOf(worst.getIncidentCount()));
        check("  two of them critical", "2", String.valueOf(worst.getCriticalIncidents()));
        check("  and is an enterprise account", "ENTERPRISE",
                worst.getCustomerType().name());
        check("  in the north", "NORTH", worst.getRegion().name());

        check("a threshold of four finds nobody", "0",
                String.valueOf(analytics.repeatIncidents(4).size()));
        // One incident is not a repeat, so a threshold below two is raised
        // to two rather than turning the analysis into a customer list.
        check("  and a threshold below two is treated as two", "3",
                String.valueOf(analytics.repeatIncidents(1).size()));

        // "Find the three engineers with the lowest active workload who have
        // the required specialization and are currently available."
        List<NetworkEngineer> lightest =
                analytics.lightestLoaded(Specialization.CORE_NETWORK, 3);
        check("the document's example returns three engineers", "3",
                String.valueOf(lightest.size()));
        check("  lightest loaded first", "ENG004, ENG002, ENG001", engineerCodes(lightest));
        check("  the one on leave is excluded although idle", "false",
                String.valueOf(engineerCodes(lightest).contains("ENG005")));
        check("  and so is the one with the wrong specialisation", "false",
                String.valueOf(engineerCodes(lightest).contains("ENG003")));
        check("asking for one gives the lightest", "ENG004",
                engineerCodes(analytics.lightestLoaded(Specialization.CORE_NETWORK, 1)));
        check("  a specialisation nobody has gives nobody", "0",
                String.valueOf(analytics.lightestLoaded(Specialization.TRANSMISSION, 3).size()));
    }

    /* ---------- 6. The Streams against the SQL ---------- */

    private static void verifyAgainstSql(DAOFactory factory) {
        section("6. The same questions asked of the database");

        TicketAnalytics analytics = TicketAnalytics.loadAll(factory);
        ReportDAO views = factory.getReportDAO();

        check("the live snapshot holds tickets", "true", String.valueOf(analytics.size() > 0));

        /*
         * Each comparison below reports how many of the view's rows the
         * Java answer agreed on, against how many rows the view returned.
         * A plain true or false would also be reported as passing when
         * the view came back empty and nothing was ever compared, which
         * is the one outcome a cross check must not call a pass.
         */

        // vw_sla_compliance. The view only produces a band that has
        // tickets, so the view leads and the Java answer is looked up.
        List<SlaComplianceDTO> sqlCompliance = views.findSlaCompliance();
        Map<Priority, SlaOutcome> javaCompliance = byPriority(analytics.slaBreachAnalysis());
        String bands = expected(sqlCompliance.size());
        int found = 0;
        int totalsAgree = 0;
        int metAgree = 0;
        int breachedAgree = 0;
        int complianceAgrees = 0;
        int hoursAgree = 0;
        for (SlaComplianceDTO row : sqlCompliance) {
            SlaOutcome mine = javaCompliance.get(row.getPriority());
            if (mine == null) {
                continue;
            }
            found++;
            totalsAgree += row.getTotalTickets() == mine.getTotal() ? 1 : 0;
            metAgree += row.getMetSla() == mine.getMet() ? 1 : 0;
            breachedAgree += row.getBreachedSla() == mine.getBreached() ? 1 : 0;
            complianceAgrees += near(row.getCompliancePercent(),
                    mine.findCompliancePercent().orElse(null)) ? 1 : 0;
            hoursAgree += near(row.getAvgResolutionHours(),
                    mine.findAverageResolutionHours().orElse(null)) ? 1 : 0;
        }
        check("every SLA band the view returns was also analysed in Java", bands,
                actual(found));
        check("  the totals match", bands, actual(totalsAgree));
        check("  the met counts match", bands, actual(metAgree));
        check("  the breach counts match", bands, actual(breachedAgree));
        check("  the compliance percentages match", bands, actual(complianceAgrees));
        check("  and so do the average resolution times", bands, actual(hoursAgree));

        // vw_engineer_workload.
        List<EngineerWorkloadDTO> sqlWorkload = views.findEngineerWorkload();
        Map<Long, EngineerScorecard> javaWorkload = analytics.engineerScorecards().stream()
                .collect(Collectors.toMap(EngineerScorecard::getEngineerId, card -> card));
        String roster = expected(sqlWorkload.size());
        int scored = 0;
        int assignedAgree = 0;
        int resolvedAgree = 0;
        int openAgree = 0;
        int breachAgree = 0;
        int engineerHoursAgree = 0;
        for (EngineerWorkloadDTO row : sqlWorkload) {
            EngineerScorecard mine = javaWorkload.get(row.getEngineerId());
            if (mine == null) {
                continue;
            }
            scored++;
            assignedAgree += row.getTotalAssigned() == mine.getAssigned() ? 1 : 0;
            resolvedAgree += row.getTotalResolved() == mine.getResolved() ? 1 : 0;
            openAgree += row.getCurrentlyOpen() == mine.getOpen() ? 1 : 0;
            breachAgree += row.getSlaBreaches() == mine.getBreaches() ? 1 : 0;
            engineerHoursAgree += near(row.getAvgResolutionHours(),
                    mine.findAverageResolutionHours().orElse(null)) ? 1 : 0;
        }
        check("every engineer in the view was scored in Java", roster, actual(scored));
        check("  the assigned counts match", roster, actual(assignedAgree));
        check("  the resolved counts match", roster, actual(resolvedAgree));
        check("  the open counts match", roster, actual(openAgree));
        check("  the breach counts match", roster, actual(breachAgree));
        check("  and the averages match", roster, actual(engineerHoursAgree));

        // vw_category_incidents.
        List<CategoryIncidentDTO> sqlCategories = views.findCategoryIncidents();
        Map<IncidentCategory, Tally<IncidentCategory>> javaCategories =
                analytics.byCategory().stream()
                        .collect(Collectors.toMap(Tally::getKey, tally -> tally));
        Map<IncidentCategory, Long> javaCritical =
                analytics.countBy(TroubleTicket::getCategory, TicketAnalytics::isCritical);
        Map<IncidentCategory, Long> javaAuto =
                analytics.countBy(TroubleTicket::getCategory, TroubleTicket::isAutoCreated);
        Map<IncidentCategory, Double> javaCategoryHours =
                analytics.averageResolutionHoursByCategory();
        String kinds = expected(sqlCategories.size());
        int categoryCounts = 0;
        int criticalCounts = 0;
        int autoCounts = 0;
        int shares = 0;
        int categoryHours = 0;
        for (CategoryIncidentDTO row : sqlCategories) {
            Tally<IncidentCategory> mine = javaCategories.get(row.getCategory());
            if (mine == null) {
                continue;
            }
            categoryCounts += row.getTicketCount() == mine.getCount() ? 1 : 0;
            criticalCounts += row.getCriticalCount()
                    == value(javaCritical, row.getCategory()) ? 1 : 0;
            autoCounts += row.getAutoCreatedCount()
                    == value(javaAuto, row.getCategory()) ? 1 : 0;
            shares += near(row.getPctOfTotal(), mine.getShare()) ? 1 : 0;
            categoryHours += near(row.getAvgResolutionHours(),
                    javaCategoryHours.get(row.getCategory())) ? 1 : 0;
        }
        check("the category counts match", kinds, actual(categoryCounts));
        check("  the critical counts within them match", kinds, actual(criticalCounts));
        check("  the automatically raised counts match", kinds, actual(autoCounts));
        check("  the shares of the total match", kinds, actual(shares));
        check("  and the resolution averages match", kinds, actual(categoryHours));

        // vw_customer_repeat_incidents, whose HAVING clause is the same
        // rule as the threshold of two.
        List<RepeatIncidentDTO> sqlRepeats = views.findRepeatIncidents();
        Map<Long, RepeatCustomer> javaRepeats = analytics.repeatIncidents(2).stream()
                .collect(Collectors.toMap(RepeatCustomer::getCustomerId, repeat -> repeat));
        String repeaters = expected(sqlRepeats.size());
        int repeatCounts = 0;
        int repeatCritical = 0;
        for (RepeatIncidentDTO row : sqlRepeats) {
            RepeatCustomer mine = javaRepeats.get(row.getCustomerId());
            if (mine == null) {
                continue;
            }
            repeatCounts += row.getIncidentCount() == mine.getIncidentCount() ? 1 : 0;
            repeatCritical += row.getCriticalIncidents() == mine.getCriticalIncidents() ? 1 : 0;
        }
        check("the same customers are flagged as repeating", repeaters,
                actual(javaRepeats.size()));
        check("  with the same incident counts", repeaters, actual(repeatCounts));
        check("  and the same critical counts", repeaters, actual(repeatCritical));

        check("the open ticket count matches the dashboard view", "true",
                String.valueOf(analytics.countOpen()
                        == views.findDashboardStats().getTotalOpenTickets()));
        check("  and so does the critical open count", "true",
                String.valueOf(analytics.countCriticalOpen()
                        == views.findDashboardStats().getCriticalIncidents()));
    }

    /* ---------- 7. The table model ---------- */

    private static void verifyTableModel() {
        section("7. What a report is made of");

        ReportTable table = ReportTable.of(ReportKind.SLA_COMPLIANCE)
                .title("A test report")
                .scope("Covering nothing in particular")
                .generatedAt(BASE)
                .text("Name")
                .number("Count")
                .number("Hours")
                .row("First", 3, 1.5d)
                .row("Second", 0, null)
                .note("Two rows")
                .build();

        check("a table knows its kind", "SLA_COMPLIANCE", table.getKind().name());
        check("  its title", "A test report", table.getTitle());
        check("  its scope", "Covering nothing in particular", table.getScope());
        check("  its columns", "Name, Count, Hours", String.join(", ", table.getHeaders()));
        check("  and its rows", "2", String.valueOf(table.getRowCount()));

        check("a number is written to two places", "1.50", table.getRows().get(0).get(2));
        check("  and something absent becomes a dash", "-", table.getRows().get(1).get(2));
        check("  a count stays a count", "3", table.getRows().get(0).get(1));

        ReportTable typed = ReportTable.of(ReportKind.CRITICAL_INCIDENT)
                .text("When").text("What").text("Blank")
                .row(BASE, Priority.CRITICAL, "   ")
                .build();
        check("a timestamp takes the document's format", "01-Mar-2026 09:00",
                typed.getRows().get(0).get(0));
        check("  an enum prints its label, not its constant", "Critical",
                typed.getRows().get(0).get(1));
        check("  and whitespace counts as absent", "-", typed.getRows().get(0).get(2));

        check("a row before any column is refused", "IllegalStateException",
                nameOfThrown(() -> ReportTable.of(ReportKind.TICKET_VOLUME).row("x")));
        check("  a row of the wrong width is refused", "IllegalStateException",
                nameOfThrown(() -> ReportTable.of(ReportKind.TICKET_VOLUME)
                        .text("One").text("Two").row("only one")));
        check("  and a table with no columns is refused", "IllegalStateException",
                nameOfThrown(() -> ReportTable.of(ReportKind.TICKET_VOLUME).build()));

        // The table crosses a thread boundary, so it has to be unmodifiable
        // rather than merely documented as read only.
        check("the rows cannot be changed afterwards", "UnsupportedOperationException",
                nameOfThrown(() -> table.getRows().clear()));
        check("  nor the columns", "UnsupportedOperationException",
                nameOfThrown(() -> table.getColumns().clear()));
        check("  nor the notes", "UnsupportedOperationException",
                nameOfThrown(() -> table.getNotes().clear()));
    }

    /* ---------- 8. CSV ---------- */

    private static void verifyCsv() {
        section("8. Exporting as CSV");

        ReportWriter writer = ReportFormat.CSV.newWriter();
        check("the writer knows its extension", "csv", writer.getExtension());
        check("  and its format", "CSV", writer.getFormat().name());

        ReportTable table = ReportTable.of(ReportKind.INCIDENT_CATEGORY)
                .title("Quoting")
                .text("Value").text("Note")
                .row("plain", "nothing special")
                .row("with, comma", "needs quoting")
                .row("with \"quotes\"", "doubled")
                .row("with\nnewline", "one field, two lines")
                .row("  padded  ", "spaces preserved")
                .build();

        String csv = writer.render(table);
        String[] lines = csv.split("\\R");

        check("the header comes first", "Value,Note", lines[0]);
        check("  a plain row needs no quotes", "plain,nothing special", lines[1]);
        // The three values below are exactly the ones that corrupt a naive
        // writer: each would otherwise change the shape of the file.
        check("  a comma is quoted so it does not invent a column",
                "\"with, comma\",needs quoting", lines[2]);
        check("  a quote is doubled inside quotes",
                "\"with \"\"quotes\"\"\",doubled", lines[3]);
        check("  a newline is quoted so it does not invent a row", "true",
                String.valueOf(csv.contains("\"with\nnewline\"")
                        || csv.contains("\"with\r\nnewline\"")));
        check("  and padding is quoted so it survives the round trip", "true",
                String.valueOf(csv.contains("\"  padded  \"")));

        // Five rows and a header is six records, but seven lines: the
        // quoted newline wraps one record across two of them, which is
        // what a reader of the raw file sees and what a parser must not
        // mistake for an extra row.
        check("a quoted newline wraps one record over two lines", "7",
                String.valueOf(csv.split("\\R").length));
        check("  with no title or preamble a spreadsheet would misread", "false",
                String.valueOf(csv.contains("Quoting")));

        ReportWriter pipes = new com.amdocs.telecom.report.CsvReportWriter("|");
        check("the delimiter is configurable", "Value|Note",
                pipes.render(table).split("\\R")[0]);
        check("  and quoting follows it", "true",
                String.valueOf(pipes.render(ReportTable.of(ReportKind.INCIDENT_CATEGORY)
                        .text("A").row("a|b").build()).contains("\"a|b\"")));
    }

    /* ---------- 9. Text ---------- */

    private static void verifyText() {
        section("9. Exporting as text");

        ReportWriter writer = ReportFormat.TEXT.newWriter();
        check("the writer knows its extension", "txt", writer.getExtension());

        ReportTable table = ReportTable.of(ReportKind.ENGINEER_PERFORMANCE)
                .title("A readable report")
                .scope("Covering the fixture")
                .generatedAt(BASE)
                .text("Engineer").number("Open")
                .row("ENG001", 3)
                .row("A much longer engineer name", 12)
                .note("Two engineers")
                .build();

        String text = writer.render(table);
        check("the title is printed", "true", String.valueOf(text.contains("A readable report")));
        check("  with the scope", "true", String.valueOf(text.contains("Covering the fixture")));
        check("  and when it was generated", "true",
                String.valueOf(text.contains("Generated 01-Mar-2026 09:00")));
        check("  the row count is stated", "true", String.valueOf(text.contains("2 row(s)")));
        check("  and the notes appear underneath", "true",
                String.valueOf(text.contains("Two engineers")));

        String[] lines = text.split("\\R");
        String header = lineContaining(lines, "Engineer");
        String shortRow = lineContaining(lines, "ENG001");
        String longRow = lineContaining(lines, "A much longer");
        // Columns are measured against their contents, so the heading and
        // both rows end at the same column whatever is in them.
        check("the heading and every row end at the same column", "true",
                String.valueOf(header.length() == shortRow.length()
                        && shortRow.length() == longRow.length()));
        check("  a column is as wide as its longest value, not its heading", "true",
                String.valueOf(header.length() > "Engineer  Open".length()));
        check("  the rules span exactly that width", "true",
                String.valueOf(lines[0].length() == header.length()
                        && lines[0].startsWith("===")));
        check("  numbers line up on the right", "true",
                String.valueOf(shortRow.endsWith(" 3") && longRow.endsWith("12")));
        check("  and nothing trails in spaces", "true",
                String.valueOf(!shortRow.endsWith(" ")));

        ReportTable empty = ReportTable.of(ReportKind.CRITICAL_INCIDENT)
                .text("Ticket").build();
        String emptyText = writer.render(empty);
        check("an empty report says so rather than printing nothing", "true",
                String.valueOf(emptyText.contains("(nothing to report)")));
        check("  and still reports zero rows", "true",
                String.valueOf(emptyText.contains("0 row(s)")));

        check("a report renders itself as its detail block", "true",
                String.valueOf(table.toDetailBlock().contains("A readable report")));
    }

    /* ---------- 10. The seven reports ---------- */

    private static void verifyTheSevenReports(DAOFactory factory) {
        section("10. The seven reports of section 18");

        check("the document asks for seven", "7", String.valueOf(ReportKind.values().length));
        check("  named as it names them",
                "Ticket Volume Report, SLA Compliance Report, Engineer Performance Report, "
                        + "Incident Category Report, Regional Incident Report, "
                        + "Average Resolution Report, Critical Incident Report",
                Arrays.stream(ReportKind.values()).map(ReportKind::getDisplayName)
                        .collect(Collectors.joining(", ")));
        check("  only the volume report is about a period", "1",
                String.valueOf(Arrays.stream(ReportKind.values())
                        .filter(ReportKind::isDated).count()));

        ReportService reports = new ReportServiceImpl(factory, new ReportExporter(
                EXPORT_DIRECTORY));
        TicketAnalytics snapshot = reports.loadAnalytics();

        ReportTable compliance = reports.generate(ReportKind.SLA_COMPLIANCE, snapshot,
                null, null);
        check("SLA compliance is one row per band", "4",
                String.valueOf(compliance.getRowCount()));
        check("  and says plainly what met and breached mean", "true",
                String.valueOf(compliance.getNotes().stream()
                        .anyMatch(note -> note.contains("resolved tickets only"))));

        ReportTable engineers = reports.generate(ReportKind.ENGINEER_PERFORMANCE, snapshot,
                null, null);
        check("engineer performance is one row per engineer", "true",
                String.valueOf(engineers.getRowCount()
                        == factory.getNetworkEngineerDAO().findAll().size()));
        check("  with eleven columns", "11", String.valueOf(engineers.getColumnCount()));

        ReportTable categories = reports.generate(ReportKind.INCIDENT_CATEGORY, snapshot,
                null, null);
        check("the category report covers every category", "10",
                String.valueOf(categories.getRowCount()));

        ReportTable regions = reports.generate(ReportKind.REGIONAL_INCIDENT, snapshot,
                null, null);
        check("the regional report covers every region", "5",
                String.valueOf(regions.getRowCount()));

        ReportTable resolution = reports.generate(ReportKind.AVERAGE_RESOLUTION, snapshot,
                null, null);
        check("the resolution report opens with the overall figure", "Overall",
                resolution.getRows().get(0).get(0));
        check("  then breaks down by priority", "Priority",
                resolution.getRows().get(1).get(0));

        ReportTable critical = reports.generate(ReportKind.CRITICAL_INCIDENT, snapshot,
                null, null);
        check("the critical report matches the critical tickets", "true",
                String.valueOf(critical.getRowCount()
                        == snapshot.criticalIncidents(false).size()));

        LocalDate today = LocalDate.now();
        ReportTable volume = reports.generate(ReportKind.TICKET_VOLUME, snapshot,
                today.minusDays(6), today);
        check("a seven day window gives seven rows", "7", String.valueOf(volume.getRowCount()));
        check("  and says which days it covers", "true",
                String.valueOf(volume.getScope().startsWith("Covering ")));

        ReportTable defaulted = reports.generate(ReportKind.TICKET_VOLUME, snapshot, null, null);
        check("no window defaults to the last thirty days", "30",
                String.valueOf(defaulted.getRowCount()));
        check("a window that ends before it starts is refused", "IllegalArgumentException",
                nameOfThrown(() -> reports.generate(ReportKind.TICKET_VOLUME, snapshot,
                        today, today.minusDays(1))));
        check("  and one longer than a year is refused", "IllegalArgumentException",
                nameOfThrown(() -> reports.generate(ReportKind.TICKET_VOLUME, snapshot,
                        today.minusYears(3), today)));

        check("a report needs a kind", "IllegalArgumentException",
                nameOfThrown(() -> reports.generate(null, snapshot, null, null)));

        Map<ReportKind, ReportTable> pack = reports.generateAll(null, null);
        check("the whole pack builds from one snapshot", "7", String.valueOf(pack.size()));
        check("  every report in it has rows or says why not", "true",
                String.valueOf(pack.values().stream()
                        .allMatch(table -> table.getColumnCount() > 0)));
    }

    /* ---------- 11. Onto the disk ---------- */

    private static void verifyExport(DAOFactory factory, ReportExporter exporter,
                                     List<Path> written) {
        section("11. Writing reports to files");

        ReportService reports = new ReportServiceImpl(factory, exporter);
        ReportTable table = reports.generate(ReportKind.SLA_COMPLIANCE);

        Path csv = exporter.export(table, ReportFormat.CSV);
        written.add(csv);
        check("the export directory is created if it is missing", "true",
                String.valueOf(Files.isDirectory(exporter.getDirectory())));
        check("  the file exists", "true", String.valueOf(Files.exists(csv)));
        check("  named for the report", "true",
                String.valueOf(csv.getFileName().toString().startsWith("rpt2_sla_compliance")));
        check("  and stamped so exports do not overwrite each other", "true",
                String.valueOf(csv.getFileName().toString()
                        .matches("rpt2_sla_compliance_\\d{8}_\\d{6}\\.csv")));

        List<String> lines = readAll(csv);
        check("what was written reads back as the header plus the rows", "5",
                String.valueOf(lines.size()));
        check("  with the headings intact", "true",
                String.valueOf(lines.get(0).startsWith("Priority,Total")));

        Path txt = exporter.export(table, ReportFormat.TEXT);
        written.add(txt);
        check("the same report goes out as text too", "true", String.valueOf(Files.exists(txt)));
        check("  under a .txt name", "true",
                String.valueOf(txt.getFileName().toString().endsWith(".txt")));
        check("  carrying the title the CSV leaves out", "true",
                String.valueOf(String.join("\n", readAll(txt))
                        .contains("SLA Compliance Report")));

        List<Path> both = reports.generateAndExport(ReportKind.REGIONAL_INCIDENT, null, null,
                ReportFormat.CSV, ReportFormat.TEXT);
        written.addAll(both);
        check("one call can write both formats", "2", String.valueOf(both.size()));
        check("  and both landed", "true",
                String.valueOf(both.stream().allMatch(Files::exists)));

        check("a preview writes nothing to disk", "true",
                String.valueOf(reports.preview(table, ReportFormat.TEXT)
                        .contains("SLA Compliance Report")));
        check("  and a format is not required for one", "true",
                String.valueOf(!reports.preview(table, null).isEmpty()));

        check("exporting without a report is refused", "IllegalArgumentException",
                nameOfThrown(() -> exporter.export(null, ReportFormat.CSV)));
        check("  and without a format", "IllegalArgumentException",
                nameOfThrown(() -> exporter.export(table, null)));
    }

    /* ---------- The fixture ---------- */

    /**
     * Seven tickets whose every answer can be worked out on paper.
     *
     * <p>Built in memory rather than seeded, so the expected numbers in
     * the checks above are facts about this data rather than a record of
     * whatever the database happened to contain the day they were
     * written.</p>
     */
    private static TicketAnalytics fixture() {
        Customer enterprise = customer(1L, "CUST001", CustomerType.ENTERPRISE, Region.NORTH);
        Customer sme = customer(2L, "CUST002", CustomerType.SME, Region.SOUTH);
        Customer consumer = customer(3L, "CUST003", CustomerType.CONSUMER, Region.NORTH);

        List<NetworkEngineer> engineers = Arrays.asList(
                engineer(11L, "ENG001", Specialization.CORE_NETWORK, Region.NORTH, 9,
                        EngineerAvailability.AVAILABLE, 2),
                engineer(12L, "ENG002", Specialization.CORE_NETWORK, Region.NORTH, 5,
                        EngineerAvailability.AVAILABLE, 1),
                engineer(13L, "ENG003", Specialization.RAN, Region.SOUTH, 7,
                        EngineerAvailability.BUSY, 0),
                engineer(14L, "ENG004", Specialization.CORE_NETWORK, Region.WEST, 3,
                        EngineerAvailability.AVAILABLE, 0),
                engineer(15L, "ENG005", Specialization.CORE_NETWORK, Region.EAST, 10,
                        EngineerAvailability.ON_LEAVE, 0));

        LocalDateTime nextDay = BASE.plusDays(1);
        List<TroubleTicket> tickets = Arrays.asList(
                // Resolved inside its two hour window: one hour.
                ticket("TT-FIX-000001", 1L, 11L, IncidentCategory.NETWORK_OUTAGE,
                        Priority.CRITICAL, TicketStatus.CLOSED, BASE, BASE.plusHours(2),
                        BASE.plusHours(1)),
                // Resolved an hour after the same window closed: three hours.
                ticket("TT-FIX-000002", 1L, 11L, IncidentCategory.NETWORK_OUTAGE,
                        Priority.CRITICAL, TicketStatus.RESOLVED, BASE, BASE.plusHours(2),
                        BASE.plusHours(3)),
                ticket("TT-FIX-000003", 1L, 12L, IncidentCategory.CALL_DROP, Priority.HIGH,
                        TicketStatus.IN_PROGRESS, BASE, BASE.plusHours(4), null),
                ticket("TT-FIX-000004", 2L, 12L, IncidentCategory.SLOW_DATA, Priority.MEDIUM,
                        TicketStatus.OPEN, BASE, BASE.plusHours(12), null),
                // Cancelled: in the total, in neither outcome, not open.
                ticket("TT-FIX-000005", 2L, null, IncidentCategory.NETWORK_OUTAGE,
                        Priority.CRITICAL, TicketStatus.CANCELLED, BASE, BASE.plusHours(2),
                        null),
                // A day later, so the repeat ordering has something to sort on.
                ticket("TT-FIX-000006", 3L, 11L, IncidentCategory.BROADBAND, Priority.LOW,
                        TicketStatus.RESOLVED, nextDay, nextDay.plusHours(48),
                        nextDay.plusHours(24)),
                ticket("TT-FIX-000007", 3L, 13L, IncidentCategory.CALL_DROP, Priority.HIGH,
                        TicketStatus.ESCALATED, nextDay, nextDay.plusHours(4), null));

        return TicketAnalytics.over(tickets, Arrays.asList(enterprise, sme, consumer),
                engineers);
    }

    private static Customer customer(Long id, String number, CustomerType type, Region region) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setCustomerNumber(number);
        customer.setCustomerName("Fixture " + number);
        customer.setCustomerType(type);
        customer.setRegion(region);
        customer.setCity("Fixture City");
        customer.setStatus(CustomerStatus.ACTIVE);
        return customer;
    }

    private static NetworkEngineer engineer(Long id, String code, Specialization specialization,
                                            Region region, int experience,
                                            EngineerAvailability availability, int active) {
        NetworkEngineer engineer = new NetworkEngineer();
        engineer.setId(id);
        engineer.setEmployeeCode(code);
        engineer.setEngineerName("Fixture " + code);
        engineer.setSpecialization(specialization);
        engineer.setRegion(region);
        engineer.setExperienceYears(experience);
        engineer.setAvailability(availability);
        engineer.setActiveTicketCount(active);
        return engineer;
    }

    private static TroubleTicket ticket(String number, Long customerId, Long engineerId,
                                        IncidentCategory category, Priority priority,
                                        TicketStatus status, LocalDateTime created,
                                        LocalDateTime deadline, LocalDateTime resolved) {
        TroubleTicket ticket = new TroubleTicket();
        ticket.setTicketNumber(number);
        ticket.setCustomerId(customerId);
        ticket.setServiceId(customerId);
        ticket.setAssignedEngineerId(engineerId);
        ticket.setCategory(category);
        ticket.setDescription("Fixture ticket " + number);
        ticket.setPriority(priority);
        ticket.setSeverity(Severity.MAJOR);
        ticket.setStatus(status);
        ticket.setCreatedDate(created);
        ticket.setSlaDeadline(deadline);
        ticket.setResolutionDate(resolved);
        ticket.setSlaStatus(resolved != null && resolved.isAfter(deadline)
                ? SLAStatus.BREACHED : SLAStatus.WITHIN_SLA);
        return ticket;
    }

    /* ---------- Small helpers ---------- */

    private static int cleanUp(List<Path> written, Path directory) {
        int removed = 0;
        for (Path file : written) {
            try {
                if (Files.deleteIfExists(file)) {
                    removed++;
                }
            } catch (IOException failure) {
                AppLogger.warn(ReportVerification.class, "Could not remove " + file);
            }
        }
        try {
            // Only if empty: never remove anything somebody else put there.
            Files.deleteIfExists(directory);
        } catch (IOException tolerated) {
            AppLogger.debug(ReportVerification.class,
                    "Left the verification export directory in place");
        }
        return removed;
    }

    private static List<String> readAll(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read back " + file, failure);
        }
    }

    /**
     * Whether two figures computed by two engines are the same number.
     */
    private static boolean near(Double left, Double right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return Math.abs(left - right) <= TOLERANCE;
    }

    private static boolean near(Double left, double right) {
        return left != null && Math.abs(left - right) <= TOLERANCE;
    }

    /**
     * How many rows the view returned, phrased so a cross check that
     * compared nothing at all fails rather than passing quietly.
     */
    private static String expected(int viewRows) {
        return viewRows == 0 ? "(the view returned no rows)" : viewRows + " agreeing";
    }

    private static String actual(int agreed) {
        return agreed + " agreeing";
    }

    private static <T> long value(Map<T, Long> counts, T key) {
        Long count = counts.get(key);
        return count == null ? 0L : count;
    }

    private static Map<Priority, SlaOutcome> byPriority(List<SlaOutcome> outcomes) {
        return outcomes.stream()
                .collect(Collectors.toMap(SlaOutcome::getPriority, outcome -> outcome));
    }

    private static <T> String countOf(List<Tally<T>> tallies, T key) {
        for (Tally<T> tally : tallies) {
            if (key.equals(tally.getKey())) {
                return String.valueOf(tally.getCount());
            }
        }
        return "(absent)";
    }

    private static <T> double shareOf(List<Tally<T>> tallies, T key) {
        for (Tally<T> tally : tallies) {
            if (key.equals(tally.getKey())) {
                return tally.getShare();
            }
        }
        return -1.0d;
    }

    private static <T> long total(List<Tally<T>> tallies) {
        return tallies.stream().mapToLong(Tally::getCount).sum();
    }

    private static <T> String labels(List<Tally<T>> tallies) {
        return tallies.stream().map(Tally::getLabel).collect(Collectors.joining(", "));
    }

    private static String codes(List<EngineerScorecard> cards) {
        return cards.stream().map(EngineerScorecard::getEmployeeCode)
                .collect(Collectors.joining(", "));
    }

    private static String engineerCodes(List<NetworkEngineer> engineers) {
        return engineers.stream().map(NetworkEngineer::getEmployeeCode)
                .collect(Collectors.joining(", "));
    }

    private static String numbers(List<RepeatCustomer> repeats) {
        return repeats.stream().map(RepeatCustomer::getCustomerNumber)
                .collect(Collectors.joining(", "));
    }

    private static String lineContaining(String[] lines, String needle) {
        return Arrays.stream(lines).filter(line -> line.contains(needle)).findFirst()
                .orElse("");
    }

    private static double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
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
