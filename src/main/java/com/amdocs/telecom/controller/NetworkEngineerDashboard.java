package com.amdocs.telecom.controller;

import com.amdocs.telecom.dto.TicketDetailDTO;
import com.amdocs.telecom.model.Notification;
import com.amdocs.telecom.model.enums.ResolutionCode;
import com.amdocs.telecom.model.enums.TicketStatus;
import com.amdocs.telecom.security.Permission;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.EscalationService;
import com.amdocs.telecom.service.NotificationService;
import com.amdocs.telecom.service.TicketService;
import com.amdocs.telecom.service.escalation.EscalationOutcome;
import com.amdocs.telecom.util.ConsoleReader;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The engineer's screen, which the case study describes by what an
 * engineer does rather than by a numbered list.
 *
 * <h3>Where these options come from</h3>
 *
 * <p>Sections 13, 14 and 15 give explicit menus for the customer, the
 * service desk and the manager. The engineer gets no such list, but
 * section 10 is unambiguous about the job: an engineer updates root
 * cause, resolution, resolution date and resolution code, and section 9
 * lets them escalate what they cannot finish in time. Those, plus seeing
 * what they have been given, are the options here.</p>
 *
 * <h3>Only their own work</h3>
 *
 * <p>An engineer holds {@code VIEW_ASSIGNED_TICKETS}, not
 * {@code VIEW_ALL_TICKETS}, so every read is scoped to the engineer id
 * carried on the session and the service refuses anything else. The
 * queue is not a pool they pick from: assignment is the service desk's
 * job under section 14, and an engineer choosing their own work would
 * make the workload balancing of section 7 meaningless.</p>
 */
public final class NetworkEngineerDashboard extends Dashboard {

    private static final int INBOX_LIMIT = 25;

    /**
     * Statuses this menu reaches through an option of their own, so the
     * "update status" list does not offer them twice. Derived from the
     * menu rather than from the service, because it is a fact about this
     * screen: any working state the lifecycle gains will appear here
     * without being added.
     */
    private static final Set<TicketStatus> HANDLED_ELSEWHERE = EnumSet.of(
            TicketStatus.OPEN, TicketStatus.ASSIGNED, TicketStatus.ESCALATED,
            TicketStatus.RESOLVED, TicketStatus.CLOSED, TicketStatus.CANCELLED);

    private final TicketService tickets;
    private final EscalationService escalations;
    private final NotificationService notifications;

    public NetworkEngineerDashboard(UserSession session, ConsoleReader console,
                                    TicketService tickets, EscalationService escalations,
                                    NotificationService notifications) {
        super(session, console);
        this.tickets = tickets;
        this.escalations = escalations;
        this.notifications = notifications;
    }

    @Override
    public String getTitle() {
        return "Network Engineer Dashboard";
    }

    @Override
    protected Menu buildMenu() {
        return Menu.titled("Network Engineer Dashboard")
                .guarded("View My Tickets", Permission.VIEW_ASSIGNED_TICKETS, this::myQueue)
                .guarded("Track Ticket", Permission.VIEW_ASSIGNED_TICKETS, this::trackTicket)
                .guarded("Update Ticket Status", Permission.UPDATE_TICKET_STATUS,
                        this::updateStatus)
                .guarded("Record Diagnosis", Permission.RECORD_DIAGNOSIS, this::recordDiagnosis)
                .guarded("Resolve Ticket", Permission.RESOLVE_TICKET, this::resolveTicket)
                .guarded("Escalate Ticket", Permission.ESCALATE_TICKET, this::escalateTicket)
                .guarded("View Notifications", Permission.VIEW_NOTIFICATIONS,
                        this::viewNotifications)
                .exit("Logout")
                .build();
    }

    /* ---------- 1. My queue ---------- */

    private void myQueue() {
        List<TicketDetailDTO> mine = tickets.listAssignedTo(session, engineerId());
        table("Assigned to you", TicketView.queueHeading(), mine, TicketView::queueRow,
                "Nothing is assigned to you.");
        if (mine.isEmpty()) {
            return;
        }
        long overdue = mine.stream().filter(ticket -> ticket.getMinutesRemaining() != null
                && ticket.getMinutesRemaining() < 0L).count();
        if (overdue > 0L) {
            note(overdue + " of these are past their SLA deadline.");
            blank();
        }
    }

    /* ---------- 2. Track ---------- */

    private void trackTicket() {
        Optional<String> number = askTicketNumber();
        if (!number.isPresent()) {
            cancelled();
            return;
        }
        TicketDetailDTO ticket = tickets.track(session, number.get());
        heading("Ticket " + ticket.getTicketNumber());
        block(ticket.toDetailBlock());
        blank();
    }

    /* ---------- 3. Update status ---------- */

    private void updateStatus() {
        Optional<String> number = askTicketNumber();
        if (!number.isPresent()) {
            cancelled();
            return;
        }

        List<TicketStatus> available = new ArrayList<TicketStatus>();
        for (TicketStatus next : tickets.nextStatesFor(session, number.get())) {
            if (!HANDLED_ELSEWHERE.contains(next)) {
                available.add(next);
            }
        }
        if (available.isEmpty()) {
            done("There is no working state " + number.get()
                    + " can move to from here. Use resolve or escalate instead.");
            blank();
            return;
        }

        Optional<TicketStatus> target = askEnum("  Move it to: ", available);
        if (!target.isPresent()) {
            cancelled();
            return;
        }
        Optional<String> remarks = askText("  Remarks: ");
        if (!remarks.isPresent()) {
            cancelled();
            return;
        }

        tickets.changeStatus(session, number.get(), target.get(), remarks.get());
        done(number.get() + " is now " + target.get().getDisplayName() + ".");
        blank();
    }

    /* ---------- 4. Record diagnosis ---------- */

    private void recordDiagnosis() {
        Optional<String> number = askTicketNumber();
        if (!number.isPresent()) {
            cancelled();
            return;
        }
        Optional<String> rootCause = askText("  Root cause: ");
        if (!rootCause.isPresent()) {
            cancelled();
            return;
        }
        tickets.recordDiagnosis(session, number.get(), rootCause.get());
        done("Root cause recorded against " + number.get() + ".");
        blank();
    }

    /* ---------- 5. Resolve ---------- */

    private void resolveTicket() {
        Optional<String> number = askTicketNumber();
        if (!number.isPresent()) {
            cancelled();
            return;
        }

        // Section 10's four fields, asked for together because the schema
        // will not accept a resolved ticket that is missing any of them.
        heading("Resolve " + number.get());
        Optional<ResolutionCode> code = askEnum("  Resolution code: ",
                ResolutionCode.values());
        if (!code.isPresent()) {
            cancelled();
            return;
        }
        Optional<String> rootCause = askText("  Root cause: ");
        if (!rootCause.isPresent()) {
            cancelled();
            return;
        }
        Optional<String> resolution = askText("  What was done: ");
        if (!resolution.isPresent()) {
            cancelled();
            return;
        }

        tickets.resolve(session, number.get(), code.get(), rootCause.get(), resolution.get());

        TicketDetailDTO resolved = tickets.track(session, number.get());
        done("Resolved " + number.get() + " in "
                + (resolved.getResolutionHours() == null ? "-"
                        : String.format("%.2f", resolved.getResolutionHours()))
                + " hours, " + resolved.getLiveSlaStatus().getDisplayName() + ".");
        blank();
    }

    /* ---------- 6. Escalate ---------- */

    private void escalateTicket() {
        Optional<String> number = askTicketNumber();
        if (!number.isPresent()) {
            cancelled();
            return;
        }

        // Says what the ladder would do before asking, because an engineer
        // escalating their own ticket should see whether the system agrees
        // it warrants it.
        heading("Escalation position of " + number.get());
        block("  " + escalations.explain(session, number.get()));
        blank();

        Optional<String> reason = askText("  Reason for escalating (blank to go back): ");
        if (!reason.isPresent()) {
            cancelled();
            return;
        }

        EscalationOutcome outcome = escalations.escalate(session, number.get(), reason.get());
        done(outcome.getMessage());
        blank();
    }

    /* ---------- 7. Notifications ---------- */

    private void viewNotifications() {
        long unread = notifications.unreadCount(session);
        List<Notification> inbox = notifications.inbox(session, false, INBOX_LIMIT);
        table("Your notifications (" + unread + " unread)",
                String.format("%-18s %-22s %-6s %s", "WHEN", "TYPE", "READ", "MESSAGE"),
                inbox, Notification::toSummaryLine, "You have no notifications.");
        if (unread > 0L && confirm("Mark all as read?")) {
            done(notifications.markAllRead(session) + " marked as read.");
            blank();
        }
    }

    /* ---------- Shared ---------- */

    private Long engineerId() {
        return session.getEngineerId().orElseThrow(() -> new IllegalStateException(
                "The engineer dashboard was opened by an account with no engineer record: "
                        + session.getUsername()));
    }
}
