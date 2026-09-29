package com.amdocs.telecom.service.impl;

import com.amdocs.telecom.dao.CustomerDAO;
import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.NetworkEngineerDAO;
import com.amdocs.telecom.dao.NotificationDAO;
import com.amdocs.telecom.dao.TroubleTicketDAO;
import com.amdocs.telecom.dao.UserDAO;
import com.amdocs.telecom.exception.AuthorizationException;
import com.amdocs.telecom.exception.ResourceNotFoundException;
import com.amdocs.telecom.model.Customer;
import com.amdocs.telecom.model.NetworkEngineer;
import com.amdocs.telecom.model.Notification;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.UserAccount;
import com.amdocs.telecom.model.enums.AccountStatus;
import com.amdocs.telecom.model.enums.NotificationType;
import com.amdocs.telecom.model.enums.ResolutionCode;
import com.amdocs.telecom.model.enums.Role;
import com.amdocs.telecom.security.AccessControl;
import com.amdocs.telecom.security.Permission;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.NotificationService;
import com.amdocs.telecom.service.event.TicketEvent;
import com.amdocs.telecom.service.event.TicketEventListener;
import com.amdocs.telecom.service.event.TicketEventType;
import com.amdocs.telecom.util.AppLogger;
import com.amdocs.telecom.validation.ValidationResult;
import com.amdocs.telecom.validation.Validators;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Section 12's notifications, and the Observer pattern's second standard
 * listener.
 *
 * <p>The class is both the service and the listener. The alternative was a
 * separate adapter class whose whole body forwarded one call, which would
 * have added a file and explained nothing. Implementing both interfaces
 * says the same thing more directly: this service is what observes tickets
 * on behalf of the people who need telling.</p>
 *
 * <h3>Who hears about what</h3>
 *
 * <p>Section 12 lists the six triggers but not the audience, so the routing
 * below is a decision. Each rule follows from a dashboard in sections 13 to
 * 15, and {@link #recipientsFor} records the reasoning per event.</p>
 *
 * <table border="1">
 *   <caption>Routing</caption>
 *   <tr><th>Event</th><th>Recipients</th></tr>
 *   <tr><td>Ticket created</td><td>the customer</td></tr>
 *   <tr><td>Engineer assigned</td><td>the customer and the engineer</td></tr>
 *   <tr><td>SLA warning</td><td>the assigned engineer, or the service desk
 *       while nobody is assigned</td></tr>
 *   <tr><td>SLA breach</td><td>as above, plus every network manager</td></tr>
 *   <tr><td>Ticket escalated</td><td>as above, plus every network manager</td></tr>
 *   <tr><td>Ticket resolved</td><td>the customer</td></tr>
 *   <tr><td>Ticket closed</td><td>the customer</td></tr>
 * </table>
 *
 * <h3>Recipients are user accounts</h3>
 *
 * <p>{@code notifications.recipient_id} is a foreign key to {@code users},
 * not to {@code customers} or {@code network_engineers}, so a customer id
 * has to be turned into a login account before anything can be addressed to
 * them. Both links are nullable in the schema: a customer known to the
 * business may have no account yet. Those recipients are dropped with a log
 * line rather than causing the write to fail, because a missing login is
 * not a reason to refuse to resolve a ticket.</p>
 */
public final class NotificationServiceImpl implements NotificationService, TicketEventListener {

    /**
     * {@code notifications.message} is VARCHAR(500). The templates are far
     * shorter, but a resolution code or an engineer name is substituted
     * into them, so the result is trimmed to fit rather than assumed to.
     */
    private static final int MESSAGE_MAX = 500;

    /**
     * How many notifications an inbox returns when the caller does not say.
     */
    public static final int DEFAULT_INBOX_SIZE = 20;

    /**
     * The most an inbox will return in one call, so a console page cannot
     * ask for the whole table.
     */
    public static final int MAX_INBOX_SIZE = 200;

    private static final String NO_RESOLUTION_CODE = "not recorded";

    private final NotificationDAO notifications;
    private final CustomerDAO customers;
    private final NetworkEngineerDAO engineers;
    private final UserDAO users;
    private final TroubleTicketDAO tickets;

    public NotificationServiceImpl() {
        this(DAOFactory.getInstance());
    }

    public NotificationServiceImpl(DAOFactory factory) {
        this.notifications = factory.getNotificationDAO();
        this.customers = factory.getCustomerDAO();
        this.engineers = factory.getNetworkEngineerDAO();
        this.users = factory.getUserDAO();
        this.tickets = factory.getTroubleTicketDAO();
    }

    /* ---------- The listener half ---------- */

    @Override
    public String getName() {
        return "notifications";
    }

    /**
     * Narrows the events reaching {@link #onTicketEvent} to the ones that
     * carry a notification type, which is section 12's list.
     */
    @Override
    public boolean isInterestedIn(TicketEventType type) {
        return type.notifies();
    }

    @Override
    public void onTicketEvent(TicketEvent event) {
        publish(event);
    }

    /* ---------- Writing ---------- */

    @Override
    public int publish(TicketEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("An event is required");
        }
        Optional<NotificationType> maybeType = event.getType().findNotificationType();
        if (!maybeType.isPresent()) {
            return 0;
        }
        NotificationType type = maybeType.get();
        Collection<Recipient> recipients = recipientsFor(event);
        if (recipients.isEmpty()) {
            AppLogger.warn(NotificationServiceImpl.class, "No reachable recipient for "
                    + type.name() + " on ticket " + event.getTicketNumber());
            return 0;
        }

        String message = Validators.shorten(messageFor(type, event), MESSAGE_MAX);
        List<Notification> queued = new ArrayList<>(recipients.size());
        for (Recipient recipient : recipients) {
            queued.add(new Notification(recipient.userId, recipient.role, type, message,
                    event.getTicketId()));
        }
        // One round trip for the whole fan-out: an SLA breach tells the
        // engineer and every manager, which is several rows describing the
        // same moment.
        int written = notifications.insertBatch(queued);
        AppLogger.debug(NotificationServiceImpl.class, written + " " + type.name()
                + " notification(s) queued for ticket " + event.getTicketNumber());
        return written;
    }

    /**
     * Fills the type's template from the event.
     *
     * <p>The wording lives on {@link NotificationType} so every channel
     * says the same thing; this method only decides which values go into
     * the gaps.</p>
     */
    private String messageFor(NotificationType type, TicketEvent event) {
        TroubleTicket ticket = event.getTicket();
        switch (type) {
            case ENGINEER_ASSIGNED:
                // The engineer's code, which the assignment event carries
                // because the customer reading this should see ENG1042
                // rather than a row number.
                return type.format(event.getTicketNumber(), event.findToValue().orElse("-"));
            case SLA_WARNING:
                // Already formatted by the SLA engine, which owns how a
                // remaining duration reads.
                return type.format(event.getTicketNumber(), event.findNote().orElse("unknown"));
            case TICKET_ESCALATED:
                return type.format(event.getTicketNumber(),
                        event.findFromValue().orElse("-"), event.findToValue().orElse("-"));
            case TICKET_RESOLVED:
                ResolutionCode code = ticket.getResolutionCode();
                return type.format(event.getTicketNumber(),
                        code == null ? NO_RESOLUTION_CODE : code.getDisplayName());
            default:
                return type.format(event.getTicketNumber());
        }
    }

    /**
     * Who should hear about this event, without duplicates.
     *
     * <p>The map is keyed by user id so somebody who qualifies twice — a
     * manager who is also the ticket's escalation target — gets one message
     * rather than two, and {@link LinkedHashMap} keeps them in the order the
     * rules added them.</p>
     */
    private Collection<Recipient> recipientsFor(TicketEvent event) {
        Map<Long, Recipient> chosen = new LinkedHashMap<>();
        TroubleTicket ticket = event.getTicket();
        switch (event.getType()) {
            case RAISED:
                // The customer only. Section 14's service desk dashboard
                // already opens with "View Open Tickets", which is this
                // queue; a notification per operator per ticket would
                // repeat that list without adding to it.
                addCustomer(chosen, ticket);
                break;
            case ASSIGNED:
                // Both ends of the assignment: the customer learns who has
                // it, the engineer learns they have work.
                addCustomer(chosen, ticket);
                addEngineer(chosen, ticket);
                break;
            case RESOLVED:
            case CLOSED:
                // Section 13 gives the customer "View Notifications" and
                // "Submit Feedback"; being told the ticket is settled is
                // what prompts the second.
                addCustomer(chosen, ticket);
                break;
            case SLA_AT_RISK:
                // Whoever can still save it. An unassigned ticket running
                // out of time has nobody to act, so the desk that would
                // assign it is told instead.
                addOwner(chosen, ticket);
                break;
            case SLA_BREACHED:
            case ESCALATED:
                // The owner, and the managers whose dashboard in section 15
                // leads with breached tickets and who are rungs three and
                // four of section 9's ladder.
                addOwner(chosen, ticket);
                addRole(chosen, Role.NETWORK_MANAGER);
                break;
            default:
                break;
        }
        return chosen.values();
    }

    private void addCustomer(Map<Long, Recipient> chosen, TroubleTicket ticket) {
        Optional<Customer> customer = customers.findById(ticket.getCustomerId());
        if (!customer.isPresent()) {
            AppLogger.warn(NotificationServiceImpl.class, "Ticket " + ticket.getTicketNumber()
                    + " names customer " + ticket.getCustomerId() + ", which no longer exists");
            return;
        }
        Long userId = customer.get().getUserId();
        if (userId == null) {
            AppLogger.debug(NotificationServiceImpl.class, "Customer "
                    + customer.get().getCustomerNumber()
                    + " has no login account, so cannot be notified");
            return;
        }
        add(chosen, userId, Role.CUSTOMER);
    }

    private void addEngineer(Map<Long, Recipient> chosen, TroubleTicket ticket) {
        Long engineerId = ticket.getAssignedEngineerId();
        if (engineerId == null) {
            return;
        }
        Optional<NetworkEngineer> engineer = engineers.findById(engineerId);
        if (!engineer.isPresent()) {
            AppLogger.warn(NotificationServiceImpl.class, "Ticket " + ticket.getTicketNumber()
                    + " names engineer " + engineerId + ", which no longer exists");
            return;
        }
        Long userId = engineer.get().getUserId();
        if (userId == null) {
            AppLogger.debug(NotificationServiceImpl.class, "Engineer "
                    + engineer.get().getEmployeeCode()
                    + " has no login account, so cannot be notified");
            return;
        }
        add(chosen, userId, Role.NETWORK_ENGINEER);
    }

    /**
     * The engineer holding the ticket, or the whole service desk while
     * nobody is.
     */
    private void addOwner(Map<Long, Recipient> chosen, TroubleTicket ticket) {
        if (ticket.getAssignedEngineerId() != null) {
            addEngineer(chosen, ticket);
            return;
        }
        addRole(chosen, Role.SERVICE_DESK);
    }

    /**
     * Everybody in a role who could act on the message.
     *
     * <p>Disabled accounts are left out: nobody will ever sign in to read
     * them. Locked accounts are included, because a lock expires and the
     * message is still worth having when it does.</p>
     */
    private void addRole(Map<Long, Recipient> chosen, Role role) {
        for (UserAccount account : users.findByRole(role)) {
            if (account.getAccountStatus() == AccountStatus.DISABLED) {
                continue;
            }
            add(chosen, account.getId(), role);
        }
    }

    private void add(Map<Long, Recipient> chosen, Long userId, Role role) {
        if (userId == null) {
            return;
        }
        if (!chosen.containsKey(userId)) {
            chosen.put(userId, new Recipient(userId, role));
        }
    }

    /* ---------- Reading ---------- */

    @Override
    public List<Notification> inbox(UserSession actor, boolean unreadOnly, int limit) {
        AccessControl.require(actor, Permission.VIEW_NOTIFICATIONS);
        return notifications.findByRecipient(actor.getUserId(), unreadOnly, boundedLimit(limit));
    }

    @Override
    public long unreadCount(UserSession actor) {
        AccessControl.require(actor, Permission.VIEW_NOTIFICATIONS);
        return notifications.countUnread(actor.getUserId());
    }

    @Override
    public boolean markRead(UserSession actor, Long notificationId) {
        AccessControl.require(actor, Permission.VIEW_NOTIFICATIONS);
        ValidationResult result = ValidationResult.forOperation("Mark notification read");
        Validators.requireIdentifier(result, "Notification", notificationId);
        result.throwIfInvalid();

        Optional<Notification> found = notifications.findById(notificationId);
        if (!found.isPresent()) {
            throw new ResourceNotFoundException("Notification", String.valueOf(notificationId));
        }
        // Checked in Java rather than added to the WHERE clause so somebody
        // reaching for another person's notification is refused outright
        // instead of being told, misleadingly, that it was already read.
        if (!actor.getUserId().equals(found.get().getRecipientId())) {
            throw new AuthorizationException("That notification was sent to somebody else.");
        }
        return notifications.markAsRead(notificationId);
    }

    @Override
    public int markAllRead(UserSession actor) {
        AccessControl.require(actor, Permission.VIEW_NOTIFICATIONS);
        return notifications.markAllAsRead(actor.getUserId());
    }

    @Override
    public List<Notification> forTicket(UserSession actor, String ticketNumber) {
        AccessControl.require(actor, Permission.VIEW_AUDIT_LOG);
        ValidationResult result = ValidationResult.forOperation("View ticket notifications");
        Validators.requireText(result, "Ticket number", ticketNumber, 1, 20);
        result.throwIfInvalid();

        TroubleTicket ticket = tickets.findByTicketNumber(ticketNumber.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", ticketNumber));
        return notifications.findByTicketId(ticket.getId());
    }

    private static int boundedLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_INBOX_SIZE;
        }
        return Math.min(limit, MAX_INBOX_SIZE);
    }

    /**
     * One addressee: the login account and the role the message was sent to
     * them as.
     *
     * <p>The role is stored alongside the id because
     * {@code notifications.recipient_role} exists, and it exists so the
     * console can group an inbox without joining back to {@code users}.</p>
     */
    private static final class Recipient {

        private final Long userId;
        private final Role role;

        private Recipient(Long userId, Role role) {
            this.userId = userId;
            this.role = role;
        }
    }
}
