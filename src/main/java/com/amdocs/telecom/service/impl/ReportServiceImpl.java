package com.amdocs.telecom.service.impl;

import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.model.Customer;
import com.amdocs.telecom.model.NetworkEngineer;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.enums.IncidentCategory;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.report.ReportExporter;
import com.amdocs.telecom.report.ReportFormat;
import com.amdocs.telecom.report.ReportKind;
import com.amdocs.telecom.report.ReportTable;
import com.amdocs.telecom.service.ReportService;
import com.amdocs.telecom.service.analytics.EngineerScorecard;
import com.amdocs.telecom.service.analytics.SlaOutcome;
import com.amdocs.telecom.service.analytics.Tally;
import com.amdocs.telecom.service.analytics.TicketAnalytics;
import com.amdocs.telecom.util.AppLogger;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Builds the seven reports section 18 asks for.
 *
 * <h3>Every report is a table</h3>
 *
 * <p>Seven reports could easily have been seven bespoke rendering
 * methods, and then seven again for CSV. Instead each one declares its
 * columns and adds its rows, and the writers turn any table into any
 * format. Adding an eighth report is one method here and one constant in
 * {@link ReportKind}; it needs no export code at all.</p>
 *
 * <h3>Where the numbers come from</h3>
 *
 * <p>From {@link TicketAnalytics}, which is Stream work over a loaded
 * snapshot, rather than from the views. That is deliberate: the views
 * answer the same questions but only ever about the whole table, and a
 * report that can be narrowed to a region or a month is worth more than
 * one that cannot. The two are defined to agree, and the verification
 * harness runs them against each other.</p>
 *
 * <h3>Windows</h3>
 *
 * <p>Only the volume report is about a period. The rest describe the
 * present, and are given the window anyway so a caller can pass one
 * uniformly; they note the window in their scope line where it helps and
 * otherwise ignore it. A volume report with no window defaults to the
 * last thirty days, because a console prompt that insists on two dates
 * before showing anything is a console prompt people stop using.</p>
 */
public final class ReportServiceImpl implements ReportService {

    /** What a volume report covers when nobody says. */
    private static final int DEFAULT_WINDOW_DAYS = 30;

    /** Beyond this a volume report is a wall of rows nobody reads. */
    private static final int MAX_WINDOW_DAYS = 366;

    private final DAOFactory factory;
    private final ReportExporter exporter;

    public ReportServiceImpl() {
        this(DAOFactory.getInstance(), new ReportExporter());
    }

    public ReportServiceImpl(DAOFactory factory, ReportExporter exporter) {
        if (factory == null || exporter == null) {
            throw new IllegalArgumentException("A DAO factory and an exporter are required");
        }
        this.factory = factory;
        this.exporter = exporter;
    }

    /* ---------- Entry points ---------- */

    @Override
    public TicketAnalytics loadAnalytics() {
        return TicketAnalytics.loadAll(factory);
    }

    @Override
    public ReportTable generate(ReportKind kind) {
        return generate(kind, null, null);
    }

    @Override
    public ReportTable generate(ReportKind kind, LocalDate from, LocalDate to) {
        return generate(kind, loadAnalytics(), from, to);
    }

    @Override
    public ReportTable generate(ReportKind kind, TicketAnalytics analytics,
                                LocalDate from, LocalDate to) {
        if (kind == null) {
            throw new IllegalArgumentException("A report kind is required");
        }
        if (analytics == null) {
            throw new IllegalArgumentException("A snapshot to report on is required");
        }
        switch (kind) {
            case TICKET_VOLUME:
                return ticketVolume(analytics, from, to);
            case SLA_COMPLIANCE:
                return slaCompliance(analytics);
            case ENGINEER_PERFORMANCE:
                return engineerPerformance(analytics);
            case INCIDENT_CATEGORY:
                return incidentCategory(analytics);
            case REGIONAL_INCIDENT:
                return regionalIncident(analytics);
            case AVERAGE_RESOLUTION:
                return averageResolution(analytics);
            case CRITICAL_INCIDENT:
                return criticalIncident(analytics);
            default:
                throw new IllegalStateException("No builder is wired for " + kind);
        }
    }

    /**
     * Every report from one snapshot, so they all describe the same
     * instant.
     */
    @Override
    public Map<ReportKind, ReportTable> generateAll(LocalDate from, LocalDate to) {
        TicketAnalytics analytics = loadAnalytics();
        Map<ReportKind, ReportTable> pack =
                new EnumMap<ReportKind, ReportTable>(ReportKind.class);
        for (ReportKind kind : ReportKind.values()) {
            pack.put(kind, generate(kind, analytics, from, to));
        }
        return pack;
    }

    /* ---------- 1. Ticket Volume Report ---------- */

    private ReportTable ticketVolume(TicketAnalytics analytics, LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_WINDOW_DAYS - 1L) : from;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException(
                    "A volume report cannot start after it ends: " + start + " to " + end);
        }
        if (start.plusDays(MAX_WINDOW_DAYS).isBefore(end)) {
            throw new IllegalArgumentException("A volume report covers at most "
                    + MAX_WINDOW_DAYS + " days; " + start + " to " + end + " is longer");
        }

        List<Tally<LocalDate>> raised = analytics.volumeByDay(start, end);
        List<Tally<LocalDate>> resolved = analytics.resolvedByDay(start, end);

        ReportTable.Builder report = ReportTable.of(ReportKind.TICKET_VOLUME)
                .scope("Covering " + com.amdocs.telecom.model.Displayable.formatDate(start)
                        + " to " + com.amdocs.telecom.model.Displayable.formatDate(end))
                .text("Date")
                .number("Raised")
                .number("Resolved")
                .number("Net")
                .number("Share");

        long totalRaised = 0L;
        long totalResolved = 0L;
        for (int index = 0; index < raised.size(); index++) {
            Tally<LocalDate> day = raised.get(index);
            long closedThatDay = index < resolved.size() ? resolved.get(index).getCount() : 0L;
            totalRaised += day.getCount();
            totalResolved += closedThatDay;
            // Net is what the backlog did: positive means it grew that day.
            report.row(day.getKey(), day.getCount(), closedThatDay,
                    day.getCount() - closedThatDay, percent(day.getShare()));
        }

        report.note(String.format("Raised %d, resolved %d, backlog moved by %+d over %d day(s)",
                totalRaised, totalResolved, totalRaised - totalResolved, raised.size()));
        return report.build();
    }

    /* ---------- 2. SLA Compliance Report ---------- */

    private ReportTable slaCompliance(TicketAnalytics analytics) {
        ReportTable.Builder report = ReportTable.of(ReportKind.SLA_COMPLIANCE)
                .scope(scopeOf(analytics))
                .text("Priority")
                .number("Total")
                .number("Completed")
                .number("Met")
                .number("Breached")
                .number("Running")
                .number("Compliance")
                .number("Avg hours");

        long met = 0L;
        long breached = 0L;
        for (SlaOutcome outcome : analytics.slaBreachAnalysis()) {
            met += outcome.getMet();
            breached += outcome.getBreached();
            report.row(outcome.getPriority(), outcome.getTotal(), outcome.getCompleted(),
                    outcome.getMet(), outcome.getBreached(), outcome.getStillRunning(),
                    outcome.findCompliancePercent().map(ReportServiceImpl::percent)
                            .orElse("n/a"),
                    outcome.findAverageResolutionHours().orElse(null));
        }

        long decided = met + breached;
        report.note(decided == 0L
                ? "Nothing has been resolved yet, so there is no compliance figure."
                : String.format("Overall: %d of %d resolved within SLA (%s)",
                        met, decided, percent(Math.round(10000.0d * met / decided) / 100.0d)));
        // Said plainly because the number invites the opposite reading: a
        // ticket that is late but still open has not breached here.
        report.note("Met and breached count resolved tickets only; "
                + "open tickets heading for a breach are counted as running.");
        return report.build();
    }

    /* ---------- 3. Engineer Performance Report ---------- */

    private ReportTable engineerPerformance(TicketAnalytics analytics) {
        ReportTable.Builder report = ReportTable.of(ReportKind.ENGINEER_PERFORMANCE)
                .scope(scopeOf(analytics))
                .text("Code")
                .text("Engineer")
                .text("Specialisation")
                .text("Region")
                .text("Availability")
                .number("Open")
                .number("Assigned")
                .number("Resolved")
                .number("Late")
                .number("On time")
                .number("Avg hours");

        List<EngineerScorecard> cards = analytics.engineerPerformance();
        long idle = 0L;
        for (EngineerScorecard card : cards) {
            if (card.getAssigned() == 0L) {
                idle++;
            }
            report.row(card.getEmployeeCode(), card.getEngineerName(),
                    card.getSpecialization(), card.getRegion(), card.getAvailability(),
                    card.getOpen(), card.getAssigned(), card.getResolved(),
                    card.getBreaches(),
                    card.findOnTimePercent().map(ReportServiceImpl::percent).orElse("n/a"),
                    card.findAverageResolutionHours().orElse(null));
        }

        report.note(cards.size() + " engineer(s) on the roster, " + idle
                + " with nothing ever assigned");
        report.note("Ordered by tickets resolved, then by fewest missed deadlines.");
        return report.build();
    }

    /* ---------- 4. Incident Category Report ---------- */

    private ReportTable incidentCategory(TicketAnalytics analytics) {
        Map<IncidentCategory, Long> critical =
                analytics.countBy(TroubleTicket::getCategory, TicketAnalytics::isCritical);
        Map<IncidentCategory, Long> automatic =
                analytics.countBy(TroubleTicket::getCategory, TroubleTicket::isAutoCreated);
        Map<IncidentCategory, Double> hours = analytics.averageResolutionHoursByCategory();

        ReportTable.Builder report = ReportTable.of(ReportKind.INCIDENT_CATEGORY)
                .scope(scopeOf(analytics))
                .text("Category")
                .number("Tickets")
                .number("Share")
                .number("Critical")
                .number("Auto raised")
                .number("Avg hours");

        List<Tally<IncidentCategory>> ranked = analytics.byCategory();
        for (Tally<IncidentCategory> tally : ranked) {
            report.row(tally.getLabel(), tally.getCount(), percent(tally.getShare()),
                    countOf(critical, tally.getKey()), countOf(automatic, tally.getKey()),
                    hours.get(tally.getKey()));
        }

        List<Tally<IncidentCategory>> top = analytics.topCategories(3);
        report.note(top.isEmpty() ? "No incidents to categorise."
                : "Worst three: " + describe(top));
        return report.build();
    }

    /* ---------- 5. Regional Incident Report ---------- */

    private ReportTable regionalIncident(TicketAnalytics analytics) {
        Map<Region, Long> open =
                analytics.countBy(analytics::regionOf, TicketAnalytics::isStillOpen);
        Map<Region, Long> critical =
                analytics.countBy(analytics::regionOf, TicketAnalytics::isCritical);
        Map<Region, Long> late =
                analytics.countBy(analytics::regionOf, TicketAnalytics::resolvedLate);
        Map<Region, Double> hours = analytics.averageResolutionHoursBy(analytics::regionOf);

        ReportTable.Builder report = ReportTable.of(ReportKind.REGIONAL_INCIDENT)
                .scope(scopeOf(analytics))
                .text("Region")
                .number("Tickets")
                .number("Share")
                .number("Open")
                .number("Critical")
                .number("Resolved late")
                .number("Avg hours");

        for (Tally<Region> tally : analytics.byRegion()) {
            report.row(tally.getLabel(), tally.getCount(), percent(tally.getShare()),
                    countOf(open, tally.getKey()), countOf(critical, tally.getKey()),
                    countOf(late, tally.getKey()), hours.get(tally.getKey()));
        }

        long unplaced = analytics.ticketsWithoutRegion();
        if (unplaced > 0L) {
            // Worth saying loudly: the columns will not add up to the total,
            // and a reader who cannot see why will assume the report is
            // broken.
            report.note(unplaced + " ticket(s) could not be placed in a region because "
                    + "their customer is not in this snapshot, and are excluded above.");
        }
        report.note("A ticket's region is the region of the customer who reported it.");
        return report.build();
    }

    /* ---------- 6. Average Resolution Report ---------- */

    private ReportTable averageResolution(TicketAnalytics analytics) {
        ReportTable.Builder report = ReportTable.of(ReportKind.AVERAGE_RESOLUTION)
                .scope(scopeOf(analytics))
                .text("Grouping")
                .text("Value")
                .number("Resolved")
                .number("Avg hours");

        OptionalDouble overall = analytics.averageResolutionHours();
        long resolvedTotal = analytics.getTickets().stream()
                .filter(ticket -> ticket.getResolutionDate() != null).count();
        report.row("Overall", "All tickets", resolvedTotal,
                overall.isPresent() ? round2(overall.getAsDouble()) : null);

        Map<Priority, Double> byPriority = analytics.averageResolutionHoursByPriority();
        Map<Priority, Long> resolvedByPriority = analytics.countBy(TroubleTicket::getPriority,
                ticket -> ticket.getResolutionDate() != null);
        for (Priority priority : Priority.values()) {
            report.row("Priority", priority.getDisplayName(),
                    countOf(resolvedByPriority, priority), byPriority.get(priority));
        }

        Map<IncidentCategory, Double> byCategory = analytics.averageResolutionHoursByCategory();
        Map<IncidentCategory, Long> resolvedByCategory =
                analytics.countBy(TroubleTicket::getCategory,
                        ticket -> ticket.getResolutionDate() != null);
        for (Map.Entry<IncidentCategory, Double> entry : byCategory.entrySet()) {
            report.row("Category", entry.getKey().getDisplayName(),
                    countOf(resolvedByCategory, entry.getKey()), entry.getValue());
        }

        report.note(overall.isPresent()
                ? String.format("Mean resolution time across %d resolved ticket(s): %.2f hours",
                        resolvedTotal, round2(overall.getAsDouble()))
                : "Nothing has been resolved yet, so there is no average.");
        report.note("Rows with no resolved tickets show no average rather than zero.");
        return report.build();
    }

    /* ---------- 7. Critical Incident Report ---------- */

    private ReportTable criticalIncident(TicketAnalytics analytics) {
        ReportTable.Builder report = ReportTable.of(ReportKind.CRITICAL_INCIDENT)
                .scope(scopeOf(analytics))
                .text("Ticket")
                .text("Customer")
                .text("Category")
                .text("Status")
                .text("Engineer")
                .text("Raised")
                .text("SLA deadline")
                .text("SLA status")
                .number("Hours");

        List<TroubleTicket> critical = analytics.criticalIncidents(false);
        long stillOpen = 0L;
        long late = 0L;
        for (TroubleTicket ticket : critical) {
            if (TicketAnalytics.isStillOpen(ticket)) {
                stillOpen++;
            }
            if (TicketAnalytics.resolvedLate(ticket)) {
                late++;
            }
            report.row(ticket.getTicketNumber(),
                    analytics.findCustomer(ticket).map(Customer::getCustomerNumber).orElse(null),
                    ticket.getCategory(), ticket.getStatus(),
                    analytics.findEngineer(ticket).map(NetworkEngineer::getEmployeeCode)
                            .orElse(null),
                    ticket.getCreatedDate(), ticket.getSlaDeadline(), ticket.getSlaStatus(),
                    ticket.getResolutionHours().orElse(null));
        }

        report.note(String.format("%d critical incident(s): %d still open, %d resolved late",
                critical.size(), stillOpen, late));
        report.note("Ordered most urgent first: by priority, then oldest first.");
        return report.build();
    }

    /* ---------- Export ---------- */

    @Override
    public Path export(ReportTable table, ReportFormat format) {
        return exporter.export(table, format);
    }

    @Override
    public List<Path> generateAndExport(ReportKind kind, LocalDate from, LocalDate to,
                                        ReportFormat... formats) {
        ReportTable table = generate(kind, from, to);
        List<Path> written = exporter.exportAll(table, formats);
        AppLogger.info(ReportServiceImpl.class, "Exported " + kind.getDisplayName()
                + " to " + written.size() + " file(s)");
        return written;
    }

    @Override
    public String preview(ReportTable table, ReportFormat format) {
        return exporter.preview(table, format);
    }

    /* ---------- Shared ---------- */

    /**
     * What the report covers, said in one line under the title.
     */
    private static String scopeOf(TicketAnalytics analytics) {
        return analytics.size() + " ticket(s) as at "
                + com.amdocs.telecom.model.Displayable.formatDateTime(LocalDateTime.now());
    }

    private static <T> long countOf(Map<T, Long> counts, T key) {
        Long count = counts.get(key);
        return count == null ? 0L : count;
    }

    private static String percent(double value) {
        return String.format("%.2f%%", value);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private static String describe(List<Tally<IncidentCategory>> top) {
        List<String> parts = new ArrayList<String>(top.size());
        for (Tally<IncidentCategory> tally : top) {
            parts.add(tally.getLabel() + " (" + tally.getCount() + ")");
        }
        return String.join(", ", parts);
    }
}
