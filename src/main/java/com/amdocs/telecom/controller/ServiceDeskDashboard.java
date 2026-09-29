package com.amdocs.telecom.controller;

import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dto.OpenTicketDTO;
import com.amdocs.telecom.dto.SlaComplianceDTO;
import com.amdocs.telecom.dto.TicketDetailDTO;
import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.security.Permission;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.EngineerAssignmentService;
import com.amdocs.telecom.service.EscalationService;
import com.amdocs.telecom.service.ReportService;
import com.amdocs.telecom.service.SlaService;
import com.amdocs.telecom.service.TicketService;
import com.amdocs.telecom.service.assignment.AssignmentResult;
import com.amdocs.telecom.service.assignment.EngineerMatch;
import com.amdocs.telecom.service.escalation.EscalationCandidate;
import com.amdocs.telecom.service.escalation.EscalationOutcome;
import com.amdocs.telecom.service.sla.SlaEvaluation;
import com.amdocs.telecom.util.ConsoleReader;

import java.util.List;
import java.util.Optional;

/**
 * Section 14's service desk dashboard, option for option.
 *
 * <h3>One addition to the case study's list</h3>
 *
 * <p>Section 14 lists eight options and no way off the screen. Logout is
 * added as a ninth rather than replacing one of the eight, because the
 * alternative is a dashboard that can only be left by killing the
 * program.</p>
 *
 * <h3>Assign and reassign are separate on purpose</h3>
 *
 * <p>The service has one {@code assign} that handles both, since the
 * transaction of section 19 is the same either way. The screen keeps them
 * apart because the question each answers is different: assigning works
 * through the unassigned queue, while reassigning starts from a ticket
 * that already has somebody on it and needs the current holder shown
 * before anything is changed. Merging them would produce a screen that
 * asks "which ticket" with no list to choose from.</p>
 */
public final class ServiceDeskDashboard extends Dashboard {

    /** How many engineers a recommendation offers, as section 16 suggests. */
    private static final int RECOMMENDATION_COUNT = 3;

    /** How many tickets the escalation sweep considers in one pass. */
    private static final int SWEEP_LIMIT = 25;

    private final TicketService tickets;
    private final EngineerAssignmentService assignments;
    private final EscalationService escalations;
    private final SlaService sla;
    private final ReportService reports;
    private final DAOFactory factory;

    public ServiceDeskDashboard(UserSession session, ConsoleReader console,
                                TicketService tickets,
                                EngineerAssignmentService assignments,
                                EscalationService escalations, SlaService sla,
                                ReportService reports, DAOFactory factory) {
        super(session, console);
        this.tickets = tickets;
        this.assignments = assignments;
        this.escalations = escalations;
        this.sla = sla;
        this.reports = reports;
        this.factory = factory;
    }

    @Override
    public String getTitle() {
        return "Service Desk Dashboard";
    }

    @Override
    protected Menu buildMenu() {
        return Menu.titled("Service Desk Dashboard")
                .guarded("View Open Tickets", Permission.VIEW_ALL_TICKETS, this::viewOpen)
                .guarded("Assign Engineer", Permission.ASSIGN_ENGINEER, this::assignEngineer)
                .guarded("Reassign Ticket", Permission.ASSIGN_ENGINEER, this::reassign)
                .guarded("Escalate Ticket", Permission.ESCALATE_TICKET, this::escalate)
                .guarded("Update Priority", Permission.UPDATE_TICKET_PRIORITY,
                        this::updatePriority)
                .guarded("Monitor SLA", Permission.VIEW_ALL_TICKETS, this::monitorSla)
                .guarded("Close Ticket", Permission.CLOSE_TICKET, this::closeTicket)
                .guarded("Generate Reports", Permission.VIEW_REPORTS, this::generateReports)
                .exit("Logout")
                .build();
    }

    /* ---------- 1. View Open Tickets ---------- */

    private void viewOpen() {
        List<OpenTicketDTO> open = factory.getReportDAO().findOpenTickets();
        table("Open tickets", String.format("%-16s %-12s %-18s %-10s %-16s %-10s %-12s %s",
                        "TICKET", "CUSTOMER", "CATEGORY", "PRIORITY", "STATUS", "ENGINEER",
                        "SLA", "DUE"),
                open, OpenTicketDTO::toSummaryLine, "Nothing is open.");

        if (open.isEmpty()) {
            return;
        }
        long unassigned = open.stream().filter(OpenTicketDTO::isUnassigned).count();
        long breached = open.stream().filter(OpenTicketDTO::isBreached).count();
        note(unassigned + " waiting for an engineer, " + breached + " past their deadline.");
        blank();
    }

    /* ---------- 2. Assign Engineer ---------- */

    private void assignEngineer() {
        List<TroubleTicket> queue = assignments.unassignedQueue(session);
        table("Waiting for an engineer", TicketView.plainHeading(), queue,
                TicketView::plainRow, "Everything open already has an engineer.");
        if (queue.isEmpty()) {
            return;
        }

        Optional<String> number = askTicketNumber();
        if (!number.isPresent()) {
            cancelled();
            return;
        }
        assignTo(number.get());
    }

    /* ---------- 3. Reassign Ticket ---------- */

    private void reassign() {
        Optional<String> number = askTicketNumber();
        if (!number.isPresent()) {
            cancelled();
            return;
        }

        TicketDetailDTO ticket = tickets.track(session, number.get());
        heading("Reassign " + ticket.getTicketNumber());
        note("Currently with " + ticket.findEngineerCode().orElse("nobody")
                + ", status " + ticket.getStatus().getDisplayName() + ".");
        assignTo(number.get());
    }

    /**
     * The recommendation and the choice, shared by assigning and
     * reassigning because section 7's five criteria apply to both.
     */
    private void assignTo(String ticketNumber) {
        List<EngineerMatch> matches =
                assignments.recommend(session, ticketNumber, RECOMMENDATION_COUNT);
        if (matches.isEmpty()) {
            done("No engineer matches this ticket's skill and region right now.");
            blank();
            return;
        }

        heading("Recommended for " + ticketNumber);
        note(String.format("%-10s %-24s %-22s %-10s %-12s %s",
                "CODE", "ENGINEER", "SPECIALISATION", "REGION", "OPEN", "WHY"));
        for (EngineerMatch match : matches) {
            note(match.toSummaryLine());
        }
        blank();

        Optional<String> code = askText(
                "  Employee code to assign, or blank to let the system choose: ");
        AssignmentResult result = code.isPresent()
                ? assignments.assign(session, ticketNumber, code.get().toUpperCase())
                : assignments.autoAssign(session, ticketNumber);

        done(result.getMessage());
        blank();
    }

    /* ---------- 4. Escalate Ticket ---------- */

    private void escalate() {
        List<EscalationCandidate> candidates = escalations.candidates(session, SWEEP_LIMIT);
        table("Tickets the ladder would move", EscalationCandidate.summaryHeading(),
                candidates, EscalationCandidate::toSummaryLine,
                "Nothing currently warrants escalation.");

        Optional<String> number = askTicketNumber();
        if (!number.isPresent()) {
            cancelled();
            return;
        }
        Optional<String> reason = askText("  Reason: ");
        if (!reason.isPresent()) {
            cancelled();
            return;
        }

        EscalationOutcome outcome = escalations.escalate(session, number.get(), reason.get());
        done(outcome.getMessage());
        blank();
    }

    /* ---------- 5. Update Priority ---------- */

    private void updatePriority() {
        Optional<String> number = askTicketNumber();
        if (!number.isPresent()) {
            cancelled();
            return;
        }

        TicketDetailDTO ticket = tickets.track(session, number.get());
        heading("Reprioritise " + ticket.getTicketNumber());
        note("Currently " + ticket.getPriority().getDisplayName() + ", due "
                + Displayable.formatDateTime(ticket.getSlaDeadline())
                + " (" + ticket.describeTimeRemaining() + ").");

        Optional<Priority> priority = askEnum("  New priority: ", Priority.values());
        if (!priority.isPresent()) {
            cancelled();
            return;
        }
        Optional<String> reason = askText("  Why: ");
        if (!reason.isPresent()) {
            cancelled();
            return;
        }

        tickets.updatePriority(session, number.get(), priority.get(), reason.get());

        // The deadline moves with the band, so showing the old one back
        // would be showing a promise that no longer applies.
        TicketDetailDTO updated = tickets.track(session, number.get());
        done(number.get() + " is now " + updated.getPriority().getDisplayName()
                + ", due " + Displayable.formatDateTime(updated.getSlaDeadline())
                + " (" + updated.describeTimeRemaining() + ").");
        blank();
    }

    /* ---------- 6. Monitor SLA ---------- */

    private void monitorSla() {
        heading("SLA compliance by band");
        note(String.format("%-10s %6s %8s %6s %9s %10s %10s",
                "PRIORITY", "TOTAL", "DONE", "MET", "BREACHED", "COMPLIANCE", "AVG HOURS"));
        for (SlaComplianceDTO row : sla.compliance()) {
            note(row.toSummaryLine());
        }
        blank();

        List<SlaEvaluation> attention = sla.needingAttention();
        table("Needing attention now", SlaEvaluation.summaryHeading(), attention,
                SlaEvaluation::toSummaryLine, "Nothing is at risk or overdue.");

        if (attention.isEmpty()) {
            return;
        }
        long overdue = attention.stream().filter(SlaEvaluation::isBreached).count();
        note(overdue + " already breached, " + (attention.size() - overdue) + " at risk.");
        blank();
    }

    /* ---------- 7. Close Ticket ---------- */

    private void closeTicket() {
        Optional<String> number = askTicketNumber();
        if (!number.isPresent()) {
            cancelled();
            return;
        }

        TicketDetailDTO ticket = tickets.track(session, number.get());
        heading("Close " + ticket.getTicketNumber());
        note("Status " + ticket.getStatus().getDisplayName() + ", resolved "
                + Displayable.formatDateTime(ticket.getResolutionDate()));
        note("Resolution: " + Displayable.orDash(ticket.getResolution()));
        blank();

        if (!confirm("Close this ticket?")) {
            cancelled();
            return;
        }
        Optional<String> remarks = askText("  Closing remarks: ");
        if (!remarks.isPresent()) {
            cancelled();
            return;
        }

        tickets.close(session, number.get(), remarks.get());
        done(number.get() + " is closed.");
        blank();
    }

    /* ---------- 8. Generate Reports ---------- */

    private void generateReports() {
        new ReportConsole(session, console, reports).run();
    }
}
