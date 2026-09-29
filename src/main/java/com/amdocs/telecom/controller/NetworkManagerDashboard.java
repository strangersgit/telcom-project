package com.amdocs.telecom.controller;

import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dto.DashboardStatsDTO;
import com.amdocs.telecom.dto.OpenTicketDTO;
import com.amdocs.telecom.dto.SlaComplianceDTO;
import com.amdocs.telecom.model.AuditLog;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.security.Permission;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.ReportService;
import com.amdocs.telecom.service.SlaService;
import com.amdocs.telecom.service.analytics.EngineerScorecard;
import com.amdocs.telecom.service.analytics.RepeatCustomer;
import com.amdocs.telecom.service.analytics.Tally;
import com.amdocs.telecom.service.analytics.TicketAnalytics;
import com.amdocs.telecom.service.sla.SlaEvaluation;
import com.amdocs.telecom.util.AppConstants;
import com.amdocs.telecom.util.ConsoleReader;

import java.util.List;
import java.util.Map;

/**
 * Section 15's network manager dashboard.
 *
 * <h3>The figures are the screen</h3>
 *
 * <p>Section 15 gives no menu at all: it gives six numbers and the layout
 * they appear in. Those are printed on arrival and again under the first
 * option, because a manager opening this screen wants the state of the
 * operation before they want to be asked a question.</p>
 *
 * <p>The numbers come from {@code vw_dashboard_stats}, a single query
 * that aggregates in the database. The Stream analytics underneath the
 * other options could produce the same six, but pulling every ticket
 * across the wire to count them would be the wrong shape for a figure
 * that is refreshed every time somebody looks at it.</p>
 *
 * <h3>The remaining options</h3>
 *
 * <p>Everything else here is oversight rather than action, and
 * deliberately so: not one option changes a ticket. A manager who spots
 * a breach reassigns nothing from this screen, because reassigning is
 * the service desk's job and resolving is the engineer's. The only thing
 * written anywhere is an exported report file.</p>
 */
public final class NetworkManagerDashboard extends Dashboard {

    /** How many rows of the audit trail one screen shows. */
    private static final int AUDIT_LIMIT = 30;

    /** How many repeat offenders the analytics screen names. */
    private static final int REPEAT_LIMIT = 10;

    private final SlaService sla;
    private final ReportService reports;
    private final DAOFactory factory;

    public NetworkManagerDashboard(UserSession session, ConsoleReader console,
                                   SlaService sla, ReportService reports,
                                   DAOFactory factory) {
        super(session, console);
        this.sla = sla;
        this.reports = reports;
        this.factory = factory;
    }

    @Override
    public String getTitle() {
        return "Network Manager Dashboard";
    }

    @Override
    protected Menu buildMenu() {
        // Printed once on arrival, because section 15 is a display before
        // it is a menu.
        showOperationsSummary();

        return Menu.titled("Network Manager Dashboard")
                .guarded("Operations Summary", Permission.VIEW_DASHBOARD,
                        this::showOperationsSummary)
                .guarded("Open Tickets", Permission.VIEW_ALL_TICKETS, this::openTickets)
                .guarded("SLA Compliance", Permission.VIEW_ALL_TICKETS, this::slaCompliance)
                .guarded("Engineer Performance", Permission.VIEW_ANALYTICS,
                        this::engineerPerformance)
                .guarded("Incident Analytics", Permission.VIEW_ANALYTICS, this::analytics)
                .guarded("Generate Reports", Permission.VIEW_REPORTS, this::generateReports)
                .guarded("Audit Trail", Permission.VIEW_AUDIT_LOG, this::auditTrail)
                .exit("Logout")
                .build();
    }

    /* ---------- 1. The six figures of section 15 ---------- */

    private void showOperationsSummary() {
        DashboardStatsDTO stats = factory.getReportDAO().findDashboardStats();

        heading("Operations summary");
        note(String.format("%-26s : %d", "Total Open Tickets",
                stats.getTotalOpenTickets()));
        note(String.format("%-26s : %d", "Critical Incidents",
                stats.getCriticalIncidents()));
        note(String.format("%-26s : %d", "SLA At Risk", stats.getSlaAtRisk()));
        note(String.format("%-26s : %d", "SLA Breached", stats.getSlaBreached()));
        note(String.format("%-26s : %d", "Resolved Today", stats.getResolvedToday()));
        note(String.format("%-26s : %s", "Average Resolution Time",
                stats.getAvgResolutionHours() == null ? "no resolved tickets yet"
                        : String.format("%.1f Hours", stats.getAvgResolutionHours())));
        console.println("  " + AppConstants.LINE_SINGLE);
        note(stats.getTotalTickets() + " tickets in total, "
                + String.format("%.1f%%", stats.getBreachPercent()) + " of them breached.");
        blank();
    }

    /* ---------- 2. Open tickets ---------- */

    private void openTickets() {
        List<OpenTicketDTO> open = factory.getReportDAO().findOpenTickets();
        table("Everything still open", String.format(
                        "%-16s %-12s %-18s %-10s %-16s %-10s %-12s %s",
                        "TICKET", "CUSTOMER", "CATEGORY", "PRIORITY", "STATUS", "ENGINEER",
                        "SLA", "DUE"),
                open, OpenTicketDTO::toSummaryLine, "Nothing is open.");
    }

    /* ---------- 3. SLA compliance ---------- */

    private void slaCompliance() {
        heading("Compliance by band");
        note(String.format("%-10s %6s %8s %6s %9s %10s %10s",
                "PRIORITY", "TOTAL", "DONE", "MET", "BREACHED", "COMPLIANCE", "AVG HOURS"));
        for (SlaComplianceDTO row : sla.compliance()) {
            note(row.toSummaryLine());
        }
        blank();

        List<SlaEvaluation> breached = sla.breached();
        table("Breached and still open", SlaEvaluation.summaryHeading(), breached,
                SlaEvaluation::toSummaryLine, "Nothing open has breached.");

        List<SlaEvaluation> atRisk = sla.atRisk();
        table("At risk", SlaEvaluation.summaryHeading(), atRisk,
                SlaEvaluation::toSummaryLine, "Nothing open is at risk.");
    }

    /* ---------- 4. Engineer performance ---------- */

    private void engineerPerformance() {
        TicketAnalytics analytics = reports.loadAnalytics();

        table("By workload, busiest first", String.format(
                        "%-10s %-22s %-22s %-10s %6s %8s %8s %6s",
                        "CODE", "ENGINEER", "SPECIALISATION", "REGION", "OPEN", "ASSIGNED",
                        "RESOLVED", "LATE"),
                analytics.engineerWorkload(), NetworkManagerDashboard::scorecardRow,
                "There are no engineers on the roster.");

        table("By tickets resolved", String.format(
                        "%-10s %-22s %-22s %-10s %6s %8s %8s %6s",
                        "CODE", "ENGINEER", "SPECIALISATION", "REGION", "OPEN", "ASSIGNED",
                        "RESOLVED", "LATE"),
                analytics.engineerPerformance(), NetworkManagerDashboard::scorecardRow,
                "There are no engineers on the roster.");
    }

    private static String scorecardRow(EngineerScorecard card) {
        return String.format("%-10s %-22s %-22s %-10s %6d %8d %8d %6d",
                card.getEmployeeCode(), card.getEngineerName(),
                card.getSpecialization().getDisplayName(),
                card.getRegion().getDisplayName(), card.getOpen(), card.getAssigned(),
                card.getResolved(), card.getBreaches());
    }

    /* ---------- 5. Incident analytics ---------- */

    private void analytics() {
        TicketAnalytics analytics = reports.loadAnalytics();

        table("By status", String.format("%-20s %8s %8s", "STATUS", "TICKETS", "SHARE"),
                analytics.byStatus(), NetworkManagerDashboard::tallyRow, "No tickets.");

        table("By priority", String.format("%-20s %8s %8s", "PRIORITY", "TICKETS", "SHARE"),
                analytics.byPriority(), NetworkManagerDashboard::tallyRow, "No tickets.");

        table("By region", String.format("%-20s %8s %8s", "REGION", "TICKETS", "SHARE"),
                analytics.byRegion(), NetworkManagerDashboard::tallyRow, "No tickets.");

        table("Worst categories", String.format("%-20s %8s %8s",
                        "CATEGORY", "TICKETS", "SHARE"),
                analytics.topCategories(5), NetworkManagerDashboard::tallyRow,
                "No tickets.");

        heading("Average resolution time");
        if (!analytics.averageResolutionHours().isPresent()) {
            note("Nothing has been resolved yet.");
        } else {
            note(String.format("%-20s %8.2f hours", "Overall",
                    analytics.averageResolutionHours().getAsDouble()));
            Map<Priority, Double> byPriority = analytics.averageResolutionHoursByPriority();
            for (Priority priority : Priority.values()) {
                Double hours = byPriority.get(priority);
                note(String.format("%-20s %8s", priority.getDisplayName(),
                        hours == null ? "-" : String.format("%.2f hours", hours)));
            }
        }
        blank();

        List<RepeatCustomer> repeats = analytics.repeatIncidents(2);
        table("Customers back more than once",
                String.format("%-12s %-26s %-22s %-10s %8s %8s",
                        "CUSTOMER", "NAME", "TYPE", "REGION", "TICKETS", "CRITICAL"),
                repeats.size() > REPEAT_LIMIT ? repeats.subList(0, REPEAT_LIMIT) : repeats,
                NetworkManagerDashboard::repeatRow,
                "No customer has raised more than one ticket.");
    }

    private static String tallyRow(Tally<?> tally) {
        return String.format("%-20s %8d %7.2f%%", tally.getLabel(), tally.getCount(),
                tally.getShare());
    }

    private static String repeatRow(RepeatCustomer repeat) {
        return String.format("%-12s %-26s %-22s %-10s %8d %8d",
                repeat.getCustomerNumber(), repeat.getCustomerName(),
                repeat.getCustomerType().getDisplayName(),
                repeat.getRegion().getDisplayName(), repeat.getIncidentCount(),
                repeat.getCriticalIncidents());
    }

    /* ---------- 6. Reports ---------- */

    private void generateReports() {
        new ReportConsole(session, console, reports).run();
    }

    /* ---------- 7. Audit trail ---------- */

    private void auditTrail() {
        List<AuditLog> recent = factory.getAuditLogDAO().findRecent(AUDIT_LIMIT);
        table("The last " + AUDIT_LIMIT + " recorded actions",
                String.format("%-18s %-18s %-14s %-22s %s",
                        "WHEN", "ENTITY", "ID", "ACTION", "BY"),
                recent, AuditLog::toSummaryLine, "Nothing has been recorded yet.");
    }
}
