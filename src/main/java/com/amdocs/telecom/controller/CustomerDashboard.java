package com.amdocs.telecom.controller;

import com.amdocs.telecom.dto.TicketDetailDTO;
import com.amdocs.telecom.dto.TicketRequest;
import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.Feedback;
import com.amdocs.telecom.model.Notification;
import com.amdocs.telecom.model.TelecomService;
import com.amdocs.telecom.model.TicketStatusHistory;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.enums.IncidentCategory;
import com.amdocs.telecom.security.Permission;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.NotificationService;
import com.amdocs.telecom.service.TicketService;
import com.amdocs.telecom.util.ConsoleReader;

import java.util.List;
import java.util.Optional;

/**
 * Section 13's customer dashboard, option for option.
 *
 * <h3>What a customer is allowed to see</h3>
 *
 * <p>Nothing here decides that. Every call passes the session, and the
 * service refuses anything that is not this customer's own. The customer
 * id is not even taken from a prompt: it comes from the session, which
 * was set at login from the account record. A screen that asked "whose
 * tickets?" would be a screen that could be answered with somebody
 * else's number.</p>
 *
 * <p>The one place that needs saying out loud is feedback. Section 13
 * offers it unconditionally, but rating a ticket that has not been dealt
 * with yet is rating nothing, so the option checks first and says why
 * rather than storing an opinion about work in progress.</p>
 */
public final class CustomerDashboard extends Dashboard {

    /** The scale section 13's feedback is collected on. */
    private static final int MIN_RATING = 1;
    private static final int MAX_RATING = 5;

    /** How many notifications an inbox shows at once. */
    private static final int INBOX_LIMIT = 25;

    private final TicketService tickets;
    private final NotificationService notifications;

    public CustomerDashboard(UserSession session, ConsoleReader console,
                             TicketService tickets, NotificationService notifications) {
        super(session, console);
        this.tickets = tickets;
        this.notifications = notifications;
    }

    @Override
    public String getTitle() {
        return "Customer Dashboard";
    }

    @Override
    protected Menu buildMenu() {
        return Menu.titled("Customer Dashboard")
                .option("View Active Services", this::viewServices)
                .guarded("Raise Trouble Ticket", Permission.RAISE_TICKET, this::raiseTicket)
                .guarded("View My Tickets", Permission.VIEW_OWN_TICKETS, this::viewMyTickets)
                .guarded("Track Ticket", Permission.VIEW_OWN_TICKETS, this::trackTicket)
                .guarded("View Ticket History", Permission.VIEW_OWN_TICKETS, this::viewHistory)
                .guarded("View Notifications", Permission.VIEW_NOTIFICATIONS,
                        this::viewNotifications)
                .guarded("Submit Feedback", Permission.SUBMIT_FEEDBACK, this::submitFeedback)
                .exit("Logout")
                .build();
    }

    /* ---------- 1. View Active Services ---------- */

    private void viewServices() {
        List<TelecomService> services = myServices();
        table("Your services", String.format("%-12s %-28s %-24s %-12s %s",
                        "CODE", "SERVICE", "TYPE", "STATUS", "ACTIVATED"),
                services, service -> String.format("%-12s %-28s %-24s %-12s %s",
                        service.getServiceCode(),
                        service.getServiceName(),
                        service.getServiceType().getDisplayName(),
                        service.getServiceStatus().getDisplayName(),
                        service.getActivationDate()),
                "You have no services on which a ticket can be raised.");
    }

    /* ---------- 2. Raise Trouble Ticket ---------- */

    private void raiseTicket() {
        List<TelecomService> services = myServices();
        if (services.isEmpty()) {
            done("There is no active service to raise a ticket against.");
            return;
        }

        heading("Raise a trouble ticket");
        Optional<TelecomService> service = askChoice(
                "  Which service is affected? ", services);
        if (!service.isPresent()) {
            cancelled();
            return;
        }

        Optional<IncidentCategory> category = askEnum(
                "  What kind of problem? ", IncidentCategory.values());
        if (!category.isPresent()) {
            cancelled();
            return;
        }

        Optional<String> description = askText("  Describe the problem: ");
        if (!description.isPresent()) {
            cancelled();
            return;
        }

        // Priority and severity are deliberately not asked for. Left to a
        // customer every ticket is critical, and the derivation from
        // category and customer type is the operator's judgement rather
        // than the caller's.
        TroubleTicket raised = tickets.raise(session, new TicketRequest(
                service.get().getId(), category.get(), description.get()));

        done("Raised " + raised.getTicketNumber() + ".");
        note("Priority " + raised.getPriority().getDisplayName() + ", answer due by "
                + Displayable.formatDateTime(raised.getSlaDeadline()) + ".");
        blank();
    }

    /* ---------- 3. View My Tickets ---------- */

    private void viewMyTickets() {
        table("Your tickets", TicketView.customerHeading(), myTickets(),
                TicketView::customerRow, "You have not raised any tickets.");
    }

    /* ---------- 4. Track Ticket ---------- */

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

    /* ---------- 5. View Ticket History ---------- */

    private void viewHistory() {
        Optional<String> number = askTicketNumber();
        if (!number.isPresent()) {
            cancelled();
            return;
        }
        List<TicketStatusHistory> trail = tickets.historyFor(session, number.get());
        table("History of " + number.get(), String.format("%-18s %-16s %-16s %-14s %s",
                        "WHEN", "FROM", "TO", "BY", "REMARKS"),
                trail, TicketStatusHistory::toSummaryLine,
                "Nothing has been recorded against this ticket yet.");
    }

    /* ---------- 6. View Notifications ---------- */

    private void viewNotifications() {
        long unread = notifications.unreadCount(session);
        List<Notification> inbox = notifications.inbox(session, false, INBOX_LIMIT);

        table("Your notifications (" + unread + " unread)",
                String.format("%-18s %-22s %-6s %s", "WHEN", "TYPE", "READ", "MESSAGE"),
                inbox, Notification::toSummaryLine, "You have no notifications.");

        if (unread == 0L || inbox.isEmpty()) {
            return;
        }
        if (confirm("Mark all as read?")) {
            done(notifications.markAllRead(session) + " marked as read.");
        } else {
            done("Left unread.");
        }
        blank();
    }

    /* ---------- 7. Submit Feedback ---------- */

    private void submitFeedback() {
        Optional<String> number = askTicketNumber();
        if (!number.isPresent()) {
            cancelled();
            return;
        }

        TicketDetailDTO ticket = tickets.track(session, number.get());
        Optional<Feedback> existing = tickets.feedbackFor(session, number.get());
        if (existing.isPresent()) {
            done("You already rated " + number.get() + ":");
            note("  " + existing.get().toSummaryLine());
            blank();
            return;
        }

        heading("Rate the handling of " + ticket.getTicketNumber());
        note("Status " + ticket.getStatus().getDisplayName() + ", resolved "
                + Displayable.formatDateTime(ticket.getResolutionDate()));
        blank();

        Optional<Integer> rating = console.readInt(
                "  Rating, " + MIN_RATING + " (poor) to " + MAX_RATING + " (excellent): ",
                MIN_RATING, MAX_RATING);
        if (!rating.isPresent()) {
            cancelled();
            return;
        }
        Optional<String> comments = console.readLine("  Comments (optional): ");

        Feedback stored = tickets.submitFeedback(session, number.get(), rating.get(),
                comments.orElse(""));
        done("Thank you. " + stored.toStars() + " recorded against "
                + ticket.getTicketNumber() + ".");
        blank();
    }

    /* ---------- Shared ---------- */

    /**
     * The services this customer may raise a ticket against, which is also
     * the honest reading of section 13's "active services": a terminated
     * line is not something anybody can still be having trouble with.
     */
    private List<TelecomService> myServices() {
        return tickets.ticketableServicesFor(session, customerId());
    }

    private List<TicketDetailDTO> myTickets() {
        return tickets.listForCustomer(session, customerId());
    }

    /**
     * The customer this session belongs to.
     *
     * <p>Never prompted for. A staff account reaching this screen has no
     * customer of its own, which is a wiring mistake rather than something
     * to recover from politely.</p>
     */
    private Long customerId() {
        return session.getCustomerId().orElseThrow(() -> new IllegalStateException(
                "The customer dashboard was opened by an account with no customer record: "
                        + session.getUsername()));
    }
}
