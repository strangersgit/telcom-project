package com.amdocs.telecom.util;

import com.amdocs.telecom.dao.AuditLogDAO;
import com.amdocs.telecom.dao.CustomerDAO;
import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.NetworkEngineerDAO;
import com.amdocs.telecom.dao.NotificationDAO;
import com.amdocs.telecom.dao.TransactionTemplate;
import com.amdocs.telecom.dao.TroubleTicketDAO;
import com.amdocs.telecom.dao.UserDAO;
import com.amdocs.telecom.dto.TicketRequest;
import com.amdocs.telecom.model.AuditLog;
import com.amdocs.telecom.model.Customer;
import com.amdocs.telecom.model.NetworkEngineer;
import com.amdocs.telecom.model.Notification;
import com.amdocs.telecom.model.TelecomService;
import com.amdocs.telecom.model.TicketStatusHistory;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.UserAccount;
import com.amdocs.telecom.model.enums.AccountStatus;
import com.amdocs.telecom.model.enums.EscalationLevel;
import com.amdocs.telecom.model.enums.IncidentCategory;
import com.amdocs.telecom.model.enums.NotificationType;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.model.enums.ResolutionCode;
import com.amdocs.telecom.model.enums.Role;
import com.amdocs.telecom.model.enums.TicketStatus;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.NotificationService;
import com.amdocs.telecom.service.TicketService;
import com.amdocs.telecom.service.event.AuditTrailListener;
import com.amdocs.telecom.service.event.TicketEvent;
import com.amdocs.telecom.service.event.TicketEventListener;
import com.amdocs.telecom.service.event.TicketEventPublisher;
import com.amdocs.telecom.service.event.TicketEventType;
import com.amdocs.telecom.service.impl.NotificationServiceImpl;
import com.amdocs.telecom.service.impl.SlaServiceImpl;
import com.amdocs.telecom.service.impl.TicketServiceImpl;

import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Exercises the Observer pattern, the audit trail and the notifications
 * against the seeded database.
 *
 * <p>The event vocabulary and the publisher are checked on their own, since
 * a wrong answer there is unambiguous. The two listeners are then checked
 * where they actually work: writing rows, with the rows read back. Last, a
 * ticket is run through part of its life to confirm that a change nobody
 * asked to be audited is audited anyway, which is the whole point of moving
 * that work to an observer.</p>
 *
 * <p>Nothing is left behind. Every write happens inside one transaction
 * rolled back to a savepoint.</p>
 */
public final class EventVerification {

    private EventVerification() {
        throw new AssertionError("EventVerification is not instantiable");
    }

    /** A seeded ticket with no engineer, so the unassigned rules apply. */
    private static final String UNASSIGNED_TICKET = "TT-2026-004521";

    /** A seeded ticket that already has an engineer. */
    private static final String ASSIGNED_TICKET = "TT-2026-004523";

    private static final String DESCRIPTION =
            "Event probe: the access link drops for a few seconds every hour";

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

        System.out.println("  Notification and audit verification");
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println();

        try {
            verifyVocabulary();
            verifyEventConstruction(factory);
            verifyPublisher(factory);
            verifyWriting(factory);
        } catch (RuntimeException failure) {
            checksFailed++;
            System.out.println("  Verification aborted: " + failure);
            AppLogger.error(EventVerification.class, "Event verification aborted", failure);
        } finally {
            int strays = removeIssuedTickets(factory.getTroubleTicketDAO());
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
            System.out.println("  Notifications and the audit trail are working against the "
                    + "live database.");
            AppLogger.info(EventVerification.class,
                    "Event verification passed " + checksRun + " checks");
        } else {
            System.out.println("  " + checksFailed + " check(s) failed. See the detail above.");
            AppLogger.warn(EventVerification.class,
                    "Event verification had " + checksFailed + " failure(s)");
        }
        System.out.println();
        return checksFailed == 0 ? 0 : 1;
    }

    /* ---------- 1. The vocabulary ---------- */

    private static void verifyVocabulary() {
        section("1. The event vocabulary");

        check("every audit action fits the column", "true",
                String.valueOf(allActionsFit(40)));
        check("  and no two share one", "true", String.valueOf(actionsAreDistinct()));

        // Section 12 lists six triggers. TICKET_ESCALATED is the seventh,
        // added because section 9's ladder has nothing else to announce
        // itself with, so the set below is the documented six plus that one.
        Set<NotificationType> expected = EnumSet.allOf(NotificationType.class);
        check("the notifying events cover every type", expected.toString(),
                notifiedTypes().toString());

        check("creation notifies", "TICKET_CREATED",
                String.valueOf(TicketEventType.RAISED.findNotificationType().orElse(null)));
        check("assignment notifies", "ENGINEER_ASSIGNED",
                String.valueOf(TicketEventType.ASSIGNED.findNotificationType().orElse(null)));
        check("resolution notifies", "TICKET_RESOLVED",
                String.valueOf(TicketEventType.RESOLVED.findNotificationType().orElse(null)));
        check("closure notifies", "TICKET_CLOSED",
                String.valueOf(TicketEventType.CLOSED.findNotificationType().orElse(null)));

        check("working a ticket notifies nobody", "false",
                String.valueOf(TicketEventType.STATUS_CHANGED.notifies()));
        check("  nor does a diagnosis", "false",
                String.valueOf(TicketEventType.DIAGNOSIS_RECORDED.notifies()));
        check("  nor a cancellation", "false",
                String.valueOf(TicketEventType.CANCELLED.notifies()));
        check("  nor feedback", "false",
                String.valueOf(TicketEventType.FEEDBACK_SUBMITTED.notifies()));

    }

    private static boolean allActionsFit(int width) {
        for (TicketEventType type : TicketEventType.values()) {
            String action = type.getAuditAction();
            if (action == null || action.isEmpty() || action.length() > width) {
                return false;
            }
        }
        return true;
    }

    private static boolean actionsAreDistinct() {
        Set<String> seen = new HashSet<String>();
        for (TicketEventType type : TicketEventType.values()) {
            if (!seen.add(type.getAuditAction())) {
                return false;
            }
        }
        return true;
    }

    private static Set<NotificationType> notifiedTypes() {
        Set<NotificationType> found = EnumSet.noneOf(NotificationType.class);
        for (TicketEventType type : TicketEventType.values()) {
            if (type.notifies()) {
                found.add(type.findNotificationType().get());
            }
        }
        return found;
    }

    /* ---------- 2. Building an event ---------- */

    private static void verifyEventConstruction(DAOFactory factory) {
        section("2. Building an event");

        TroubleTicket ticket = seeded(factory, UNASSIGNED_TICKET);
        UserSession actor = staffSession(factory, Role.SERVICE_DESK);

        TicketEvent raised = TicketEvent.raised(ticket, actor, "a detail");
        check("the type is carried", "RAISED", raised.getType().name());
        check("  as is the ticket number", ticket.getTicketNumber(), raised.getTicketNumber());
        check("  and the ticket's key", String.valueOf(ticket.getId()),
                String.valueOf(raised.getTicketId()));
        check("  and who did it", actor.getUsername(), raised.getActor());
        check("raising has no previous value", "true",
                String.valueOf(!raised.findFromValue().isPresent()));
        check("  and lands on Open", "OPEN", raised.findToValue().orElse(null));
        check("  the detail survives", "a detail", raised.findNote().orElse(null));

        TicketEvent moved = TicketEvent.statusChanged(ticket, actor, TicketStatus.ASSIGNED,
                TicketStatus.IN_PROGRESS, "  engineer on site  ");
        check("a status change records both ends", "ASSIGNED -> IN_PROGRESS",
                moved.findFromValue().orElse("-") + " -> " + moved.findToValue().orElse("-"));
        check("  remarks are trimmed", "engineer on site", moved.findNote().orElse(null));

        TicketEvent priority = TicketEvent.priorityChanged(ticket, actor, Priority.MEDIUM,
                Priority.CRITICAL, "customer escalated");
        check("a priority change records both bands", "MEDIUM -> CRITICAL",
                priority.findFromValue().orElse("-") + " -> "
                        + priority.findToValue().orElse("-"));

        // The escalation message reads "escalated from X to Y", so the levels
        // go in as the names a person would recognise rather than as enum
        // constants.
        TicketEvent escalated = TicketEvent.escalated(ticket, actor, EscalationLevel.ENGINEER,
                EscalationLevel.TEAM_LEAD, "no progress in two hours");
        check("an escalation names the rungs", "Engineer -> Team Lead",
                escalated.findFromValue().orElse("-") + " -> "
                        + escalated.findToValue().orElse("-"));

        TicketEvent atRisk = TicketEvent.slaAtRisk(ticket, "24 minutes");
        check("an SLA warning is the system's", "SYSTEM", atRisk.getActor());
        check("  and carries the time left", "24 minutes", atRisk.findNote().orElse(null));

        check("blank notes become absent", "true", String.valueOf(
                !TicketEvent.closed(ticket, actor, TicketStatus.RESOLVED, "   ")
                        .findNote().isPresent()));

        // DATETIME holds no fractional seconds, so an event stamped with
        // nanoseconds would not match the row written from it.
        check("the timestamp has no nanoseconds", "0",
                String.valueOf(raised.getOccurredAt().getNano()));

        check("a missing ticket is refused", "IllegalArgumentException",
                nameOfThrown(() -> TicketEvent.raised(null, actor, "x")));
        check("  as is a missing actor", "IllegalArgumentException",
                nameOfThrown(() -> TicketEvent.raised(ticket, null, "x")));
        check("  as is an unsaved ticket", "IllegalArgumentException",
                nameOfThrown(() -> TicketEvent.raised(new TroubleTicket(), actor, "x")));
        check("an assignment needs the engineer's code", "IllegalArgumentException",
                nameOfThrown(() -> TicketEvent.assigned(ticket, actor, null, "  ", null)));
        check("an SLA warning needs the time left", "IllegalArgumentException",
                nameOfThrown(() -> TicketEvent.slaAtRisk(ticket, null)));
        check("an escalation needs both rungs", "IllegalArgumentException",
                nameOfThrown(() -> TicketEvent.escalated(ticket, actor,
                        EscalationLevel.ENGINEER, null, "why")));

        check("an event describes itself", "true", String.valueOf(
                raised.toString().contains("RAISED")
                        && raised.toString().contains(ticket.getTicketNumber())));
    }

    /* ---------- 3. The publisher ---------- */

    private static void verifyPublisher(DAOFactory factory) {
        section("3. The publisher and its listeners");

        TroubleTicket ticket = seeded(factory, UNASSIGNED_TICKET);
        UserSession actor = staffSession(factory, Role.SERVICE_DESK);
        TicketEvent event = TicketEvent.raised(ticket, actor, "a detail");

        TicketEventPublisher publisher = new TicketEventPublisher();
        check("a new publisher has no listeners", "0",
                String.valueOf(publisher.listeners().size()));
        check("  so publishing tells nobody", "0", String.valueOf(publisher.publish(event)));

        RecordingListener first = new RecordingListener("first");
        RecordingListener second = new RecordingListener("second");
        publisher.register(first).register(second);
        check("registration adds listeners", "2",
                String.valueOf(publisher.listeners().size()));
        check("  in the order they were added", "first, second", namesOf(publisher));
        check("  registering twice is ignored", "2",
                String.valueOf(publisher.register(first).listeners().size()));
        check("the listing cannot be edited", "UnsupportedOperationException",
                nameOfThrown(() -> publisher.listeners().clear()));

        check("both listeners are told", "2", String.valueOf(publisher.publish(event)));
        check("  and each saw the event", "RAISED, RAISED",
                first.lastType() + ", " + second.lastType());

        check("unregistering removes one", "true", String.valueOf(publisher.unregister(first)));
        check("  and it hears no more", "1", String.valueOf(publisher.publish(event)));
        check("  removing it again does nothing", "false",
                String.valueOf(publisher.unregister(first)));

        // A listener that only wants some events should not be counted as
        // having handled the rest, which is what the publisher's return
        // value reports.
        TicketEventPublisher picky = new TicketEventPublisher()
                .register(new RecordingListener("closures-only", TicketEventType.CLOSED));
        check("an uninterested listener is skipped", "0", String.valueOf(picky.publish(event)));
        check("  but hears the events it wants", "1", String.valueOf(picky.publish(
                TicketEvent.closed(ticket, actor, TicketStatus.RESOLVED, "done"))));

        // Section 19 puts the notification and the audit record inside the
        // assignment transaction and ends with "Any failure -> ROLLBACK", so
        // a listener that cannot do its job has to take the change down with
        // it rather than being quietly skipped.
        TicketEventPublisher failing = new TicketEventPublisher()
                .register(new FailingListener())
                .register(new RecordingListener("never-reached"));
        check("a failing listener stops the publish", "IllegalStateException",
                nameOfThrown(() -> failing.publish(event)));

        check("a null listener is refused", "IllegalArgumentException",
                nameOfThrown(() -> publisher.register(null)));
        check("a null event is refused", "IllegalArgumentException",
                nameOfThrown(() -> publisher.publish(null)));

        check("the shared publisher is wired", "audit-trail, notifications",
                namesOf(TicketEventPublisher.getInstance()));
        check("  and is the same one every time", "true", String.valueOf(
                TicketEventPublisher.getInstance() == TicketEventPublisher.getInstance()));
    }

    private static String namesOf(TicketEventPublisher publisher) {
        StringBuilder names = new StringBuilder();
        for (TicketEventListener listener : publisher.listeners()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(listener.getName());
        }
        return names.toString();
    }

    /* ---------- 4 to 8. What the listeners write ---------- */

    private static void verifyWriting(DAOFactory factory) {
        TroubleTicketDAO tickets = factory.getTroubleTicketDAO();
        CustomerDAO customers = factory.getCustomerDAO();
        NetworkEngineerDAO engineers = factory.getNetworkEngineerDAO();
        UserDAO users = factory.getUserDAO();

        TroubleTicket unassigned = seeded(factory, UNASSIGNED_TICKET);
        TroubleTicket assigned = seeded(factory, ASSIGNED_TICKET);

        Customer owner = customers.getById(unassigned.getCustomerId());
        UserSession customer = sessionFor(users.getById(owner.getUserId()), owner.getId(), null);

        NetworkEngineer engineer = engineers.getById(assigned.getAssignedEngineerId());
        UserSession engineerSession = sessionFor(users.getById(engineer.getUserId()), null,
                engineer.getId());

        UserSession serviceDesk = staffSession(factory, Role.SERVICE_DESK);
        UserSession manager = staffSession(factory, Role.NETWORK_MANAGER);

        TransactionTemplate.run(context -> {
            Savepoint marker = context.savepoint("event_verification");
            try {
                verifyAuditWriting(factory, unassigned, serviceDesk);
                verifyRouting(factory, unassigned, assigned, owner, engineer, serviceDesk);
                verifyWording(factory, assigned, engineer, serviceDesk);
                verifyInbox(factory, unassigned, customer, engineerSession, manager);
                verifyEndToEnd(factory, customer, engineerSession, serviceDesk, unassigned,
                        engineer.getId());
            } finally {
                context.rollbackTo(marker);
            }
        });

        check("nothing was left in the ticket table", "0",
                String.valueOf(countIssuedTickets(tickets)));
    }

    /* ---------- 4. The audit listener ---------- */

    private static void verifyAuditWriting(DAOFactory factory, TroubleTicket ticket,
                                           UserSession actor) {
        section("4. The audit listener");

        AuditLogDAO auditLog = factory.getAuditLogDAO();
        AuditTrailListener listener = new AuditTrailListener(factory);
        TicketEventPublisher publisher = new TicketEventPublisher().register(listener);

        long before = countAudit(auditLog, ticket);
        publisher.publish(TicketEvent.statusChanged(ticket, actor, TicketStatus.ASSIGNED,
                TicketStatus.IN_PROGRESS, "engineer on site"));
        check("an event writes one row", "1",
                String.valueOf(countAudit(auditLog, ticket) - before));

        AuditLog row = newestAudit(auditLog, ticket);
        check("  with the event's action", "STATUS_CHANGED", row.getAction());
        check("  the value it had", "ASSIGNED", row.getOldValue());
        check("  the value it took", "IN_PROGRESS", row.getNewValue());
        check("  the note that came with it", "engineer on site", row.getDetails());
        check("  and who did it", actor.getUsername(), row.getPerformedBy());
        check("  against the ticket's number", ticket.getTicketNumber(), row.getEntityId());
        check("  and its type", "TROUBLE_TICKET", row.getEntityType());

        // A description holds a thousand characters and the audit columns
        // five hundred, so something has to give; the listener cuts rather
        // than letting the insert fail.
        publisher.publish(TicketEvent.raised(ticket, actor, repeat("long ", 300)));
        check("over-long detail is cut to fit", "500",
                String.valueOf(newestAudit(auditLog, ticket).getDetails().length()));
        check("  and says it was cut", "true", String.valueOf(
                newestAudit(auditLog, ticket).getDetails().endsWith("...")));

        publisher.publish(TicketEvent.slaBreached(ticket, "response window missed"));
        AuditLog systemRow = newestAudit(auditLog, ticket);
        check("a monitor's event is the system's", "SYSTEM", systemRow.getPerformedBy());
        check("  and recorded as a breach", "SLA_BREACHED", systemRow.getAction());

        // The listener has no opinion about which changes matter, which is
        // the property that makes it impossible for a new operation to be
        // added without being audited.
        check("every event type is audited", "true", String.valueOf(interestedInAll(listener)));
    }

    private static boolean interestedInAll(TicketEventListener listener) {
        for (TicketEventType type : TicketEventType.values()) {
            if (!listener.isInterestedIn(type)) {
                return false;
            }
        }
        return true;
    }

    /* ---------- 5. Who hears about what ---------- */

    private static void verifyRouting(DAOFactory factory, TroubleTicket unassigned,
                                      TroubleTicket assigned, Customer owner,
                                      NetworkEngineer engineer, UserSession actor) {
        section("5. Who hears about what");

        NotificationDAO inbox = factory.getNotificationDAO();
        NotificationService notifications = new NotificationServiceImpl(factory);
        UserDAO users = factory.getUserDAO();

        int deskCount = reachable(users, Role.SERVICE_DESK);
        int managerCount = reachable(users, Role.NETWORK_MANAGER);

        clearNotifications(inbox, unassigned);
        check("raising tells the customer", "1", String.valueOf(notifications.publish(
                TicketEvent.raised(unassigned, actor, "broadband down"))));
        check("  and nobody else", "[CUSTOMER]", rolesTold(inbox, unassigned));
        check("  the right customer", String.valueOf(owner.getUserId()),
                String.valueOf(recipientsTold(inbox, unassigned).get(0)));

        clearNotifications(inbox, assigned);
        check("assignment tells customer and engineer", "2",
                String.valueOf(notifications.publish(TicketEvent.assigned(assigned, actor, null,
                        engineer.getEmployeeCode(), null))));
        check("  in that order", "[CUSTOMER, NETWORK_ENGINEER]", rolesTold(inbox, assigned));

        clearNotifications(inbox, assigned);
        check("resolution tells the customer", "1", String.valueOf(notifications.publish(
                TicketEvent.resolved(assigned, actor, TicketStatus.IN_PROGRESS, "fixed"))));
        check("  and only them", "[CUSTOMER]", rolesTold(inbox, assigned));

        clearNotifications(inbox, assigned);
        check("closure tells the customer", "1", String.valueOf(notifications.publish(
                TicketEvent.closed(assigned, actor, TicketStatus.RESOLVED, null))));
        check("  and only them", "[CUSTOMER]", rolesTold(inbox, assigned));

        // An unassigned ticket running out of time has nobody to act on it,
        // so the desk that would assign it is told instead.
        clearNotifications(inbox, unassigned);
        check("an unowned ticket at risk tells the desk", String.valueOf(deskCount),
                String.valueOf(notifications.publish(
                        TicketEvent.slaAtRisk(unassigned, "18 minutes"))));
        check("  as service desk", "true",
                String.valueOf(rolesTold(inbox, unassigned).equals(
                        repeatedRole(Role.SERVICE_DESK, deskCount))));

        clearNotifications(inbox, assigned);
        check("an owned ticket at risk tells the engineer", "1",
                String.valueOf(notifications.publish(
                        TicketEvent.slaAtRisk(assigned, "9 minutes"))));
        check("  and only them", "[NETWORK_ENGINEER]", rolesTold(inbox, assigned));

        clearNotifications(inbox, assigned);
        check("a breach tells the engineer and the managers",
                String.valueOf(1 + managerCount),
                String.valueOf(notifications.publish(
                        TicketEvent.slaBreached(assigned, "deadline passed"))));

        clearNotifications(inbox, assigned);
        check("an escalation tells the same people", String.valueOf(1 + managerCount),
                String.valueOf(notifications.publish(TicketEvent.escalated(assigned, actor,
                        EscalationLevel.ENGINEER, EscalationLevel.TEAM_LEAD, "no progress"))));
        check("  the engineer first, then a manager", "true",
                String.valueOf(rolesTold(inbox, assigned).startsWith(
                        "[NETWORK_ENGINEER, NETWORK_MANAGER")));

        clearNotifications(inbox, assigned);
        check("working a ticket tells nobody", "0", String.valueOf(notifications.publish(
                TicketEvent.statusChanged(assigned, actor, TicketStatus.ASSIGNED,
                        TicketStatus.IN_PROGRESS, null))));
        check("  and writes no row", "0", String.valueOf(inbox.findByTicketId(
                assigned.getId()).size()));

        NotificationServiceImpl listener = new NotificationServiceImpl(factory);
        check("the listener ignores the silent events", "false",
                String.valueOf(listener.isInterestedIn(TicketEventType.DIAGNOSIS_RECORDED)));
        check("  and takes the rest", "true",
                String.valueOf(listener.isInterestedIn(TicketEventType.RESOLVED)));
        check("a null event is refused", "IllegalArgumentException",
                nameOfThrown(() -> notifications.publish(null)));

        clearNotifications(inbox, assigned);
        clearNotifications(inbox, unassigned);
    }

    private static String repeatedRole(Role role, int times) {
        List<String> names = new ArrayList<String>();
        for (int index = 0; index < times; index++) {
            names.add(role.name());
        }
        return names.toString();
    }

    /* ---------- 6. What the message says ---------- */

    private static void verifyWording(DAOFactory factory, TroubleTicket assigned,
                                      NetworkEngineer engineer, UserSession actor) {
        section("6. What the message says");

        NotificationDAO inbox = factory.getNotificationDAO();
        NotificationService notifications = new NotificationServiceImpl(factory);
        String number = assigned.getTicketNumber();

        clearNotifications(inbox, assigned);
        notifications.publish(TicketEvent.raised(assigned, actor, "broadband down"));
        check("creation", NotificationType.TICKET_CREATED.format(number),
                newestMessage(inbox, assigned));

        clearNotifications(inbox, assigned);
        notifications.publish(TicketEvent.assigned(assigned, actor, null,
                engineer.getEmployeeCode(), null));
        check("assignment names the engineer",
                NotificationType.ENGINEER_ASSIGNED.format(number, engineer.getEmployeeCode()),
                newestMessage(inbox, assigned));

        clearNotifications(inbox, assigned);
        notifications.publish(TicketEvent.slaAtRisk(assigned, "12 minutes"));
        check("a warning says how long is left",
                NotificationType.SLA_WARNING.format(number, "12 minutes"),
                newestMessage(inbox, assigned));

        clearNotifications(inbox, assigned);
        notifications.publish(TicketEvent.slaBreached(assigned, "deadline passed"));
        check("a breach states the fact", NotificationType.SLA_BREACH.format(number),
                newestMessage(inbox, assigned));

        clearNotifications(inbox, assigned);
        notifications.publish(TicketEvent.escalated(assigned, actor, EscalationLevel.ENGINEER,
                EscalationLevel.NETWORK_MANAGER, "no progress"));
        check("an escalation names both rungs",
                NotificationType.TICKET_ESCALATED.format(number, "Engineer", "Network Manager"),
                newestMessage(inbox, assigned));

        // The resolution code is read off the ticket rather than the event.
        // A detached copy stands in for the resolved row here; section 8
        // confirms that the service really does hand over a row carrying the
        // code rather than the one it read before the update.
        TroubleTicket resolved = copyWithResolution(assigned, ResolutionCode.CONFIGURATION_ERROR);
        clearNotifications(inbox, assigned);
        notifications.publish(TicketEvent.resolved(resolved, actor, TicketStatus.IN_PROGRESS,
                "reconfigured"));
        check("resolution quotes the code", NotificationType.TICKET_RESOLVED.format(number,
                ResolutionCode.CONFIGURATION_ERROR.getDisplayName()),
                newestMessage(inbox, assigned));

        clearNotifications(inbox, assigned);
        notifications.publish(TicketEvent.closed(assigned, actor, TicketStatus.RESOLVED, null));
        check("closure states the fact", NotificationType.TICKET_CLOSED.format(number),
                newestMessage(inbox, assigned));
        check("  and the type is stored with it", "TICKET_CLOSED",
                String.valueOf(newestNotification(inbox, assigned).getNotificationType()));
        check("  against the ticket it concerns", String.valueOf(assigned.getId()),
                String.valueOf(newestNotification(inbox, assigned).getTicketId()));
        check("  arriving unread", "true",
                String.valueOf(newestNotification(inbox, assigned).isUnread()));
        check("every message fits the column", "true",
                String.valueOf(allMessagesFit(inbox, assigned, 500)));

        clearNotifications(inbox, assigned);
    }

    /**
     * A detached copy carrying a resolution, standing in for the row as it
     * would be after a resolve. Nothing is written, so the seeded ticket is
     * untouched.
     */
    private static TroubleTicket copyWithResolution(TroubleTicket ticket, ResolutionCode code) {
        TroubleTicket copy = new TroubleTicket();
        copy.setId(ticket.getId());
        copy.setTicketNumber(ticket.getTicketNumber());
        copy.setCustomerId(ticket.getCustomerId());
        copy.setAssignedEngineerId(ticket.getAssignedEngineerId());
        copy.setResolutionCode(code);
        return copy;
    }

    /* ---------- 7. Reading an inbox ---------- */

    private static void verifyInbox(DAOFactory factory, TroubleTicket ticket,
                                    UserSession customer, UserSession engineer,
                                    UserSession manager) {
        section("7. Reading an inbox");

        NotificationDAO inbox = factory.getNotificationDAO();
        NotificationService notifications = new NotificationServiceImpl(factory);

        clearNotifications(inbox, ticket);
        long before = notifications.unreadCount(customer);
        notifications.publish(TicketEvent.raised(ticket, customer, "one"));
        notifications.publish(TicketEvent.closed(ticket, customer, TicketStatus.RESOLVED, null));
        check("the unread count rises", "2",
                String.valueOf(notifications.unreadCount(customer) - before));

        List<Notification> recent = notifications.inbox(customer, true, 5);
        check("the inbox is the caller's own", "true", String.valueOf(
                allAddressedTo(recent, customer.getUserId())));
        check("  newest first", "TICKET_CLOSED",
                String.valueOf(recent.get(0).getNotificationType()));

        check("asking for none falls back to a page", "true",
                String.valueOf(notifications.inbox(customer, false, 0).size()
                        <= NotificationServiceImpl.DEFAULT_INBOX_SIZE));
        check("  and a huge request is capped", "true",
                String.valueOf(notifications.inbox(customer, false, 100000).size()
                        <= NotificationServiceImpl.MAX_INBOX_SIZE));

        Long newest = recent.get(0).getId();
        check("marking one read works once", "true",
                String.valueOf(notifications.markRead(customer, newest)));
        check("  and not twice", "false", String.valueOf(notifications.markRead(customer, newest)));
        check("  the read one drops out of unread", "false", String.valueOf(
                containsId(notifications.inbox(customer, true, 20), newest)));
        check("  but stays in the full list", "true", String.valueOf(
                containsId(notifications.inbox(customer, false, 20), newest)));

        check("somebody else's notification is refused", "AuthorizationException",
                nameOfThrown(() -> notifications.markRead(engineer, newest)));
        check("a notification that does not exist", "ResourceNotFoundException",
                nameOfThrown(() -> notifications.markRead(customer, 999999999L)));
        check("  a nonsense key is refused as input", "ValidationException",
                nameOfThrown(() -> notifications.markRead(customer, -1L)));

        check("marking all read clears the count", "0", String.valueOf(
                markAllThenCount(notifications, customer)));

        check("a manager may see a ticket's notifications", "true",
                String.valueOf(notifications.forTicket(manager, ticket.getTicketNumber())
                        .size() >= 2));
        check("  oldest first", "TICKET_CREATED", String.valueOf(notifications
                .forTicket(manager, ticket.getTicketNumber()).get(0).getNotificationType()));
        check("a customer may not", "AuthorizationException", nameOfThrown(() ->
                notifications.forTicket(customer, ticket.getTicketNumber())));
        check("  nor an engineer", "AuthorizationException", nameOfThrown(() ->
                notifications.forTicket(engineer, ticket.getTicketNumber())));
        check("an unknown ticket", "ResourceNotFoundException", nameOfThrown(() ->
                notifications.forTicket(manager, "TT-1999-000001")));
        check("  a blank number is refused as input", "ValidationException",
                nameOfThrown(() -> notifications.forTicket(manager, "  ")));

        check("no session at all is refused", "AuthenticationException",
                nameOfThrown(() -> notifications.unreadCount(null)));

        clearNotifications(inbox, ticket);
    }

    private static long markAllThenCount(NotificationService notifications, UserSession actor) {
        notifications.markAllRead(actor);
        return notifications.unreadCount(actor);
    }

    /* ---------- 8. A change nobody remembered to audit ---------- */

    private static void verifyEndToEnd(DAOFactory factory, UserSession customer,
                                       UserSession engineer, UserSession serviceDesk,
                                       TroubleTicket template, Long engineerId) {
        section("8. A ticket's changes, audited without being asked");

        TicketService service = new TicketServiceImpl(factory, new SlaServiceImpl(),
                TicketEventPublisher.getInstance());
        AuditLogDAO auditLog = factory.getAuditLogDAO();
        NotificationDAO inbox = factory.getNotificationDAO();

        List<TelecomService> offered =
                service.ticketableServicesFor(customer, template.getCustomerId());
        TroubleTicket raised = raise(service, customer, new TicketRequest(
                offered.get(0).getId(), IncidentCategory.BROADBAND, DESCRIPTION));
        String number = raised.getTicketNumber();

        check("raising leaves an audit row", "[TICKET_RAISED]", actionsFor(auditLog, raised));
        check("  and tells the customer", "[TICKET_CREATED]", typesTold(inbox, raised));

        // Assignment is Phase 9's, so the engineer is put on the ticket
        // through the DAO here. That also makes the point: a change made
        // without going through the service leaves no audit row, which is
        // why the service is the only sanctioned route.
        assignTo(factory, raised, engineerId);
        check("a change made behind the service is not audited", "[TICKET_RAISED]",
                actionsFor(auditLog, raised));

        service.changeStatus(engineer, number, TicketStatus.IN_PROGRESS, "picked up");
        // Two rows from one call: the move, and the first response the move
        // implies. Neither is written by changeStatus itself.
        check("working it audits the move and the response",
                "[TICKET_RAISED, STATUS_CHANGED, FIRST_RESPONSE]", actionsFor(auditLog, raised));
        check("  and tells nobody new", "[TICKET_CREATED]", typesTold(inbox, raised));

        service.recordDiagnosis(engineer, number, "Faulty line card in the access node");
        check("a diagnosis is audited",
                "[TICKET_RAISED, STATUS_CHANGED, FIRST_RESPONSE, DIAGNOSIS_RECORDED]",
                actionsFor(auditLog, raised));

        service.resolve(engineer, number, ResolutionCode.HARDWARE_FAILURE,
                "Line card had failed", "Replaced the card and confirmed the line");
        check("resolving is audited", "true",
                String.valueOf(actionsFor(auditLog, raised).contains("TICKET_RESOLVED")));
        check("  and tells the customer, naming the code", "true",
                String.valueOf(newestMessage(inbox, raised).contains(
                        ResolutionCode.HARDWARE_FAILURE.getDisplayName())));

        service.close(serviceDesk, number, "Confirmed with the customer");
        check("closing is audited", "true",
                String.valueOf(actionsFor(auditLog, raised).contains("TICKET_CLOSED")));
        check("the customer heard about three things",
                "[TICKET_CREATED, TICKET_RESOLVED, TICKET_CLOSED]",
                distinctTypesTold(inbox, raised));
        check("  every one addressed to them", "true", String.valueOf(allAddressedTo(
                inbox.findByTicketId(raised.getId()), customer.getUserId())));

        check("the audit trail reads in order", "true",
                String.valueOf(auditIsOrdered(auditLog, raised)));
        check("  and names who did each change", "true",
                String.valueOf(performersAreKnown(auditLog, raised)));
    }

    /**
     * Puts an engineer on a ticket the way the seed does, since the
     * assignment service belongs to a later phase. The history row goes with
     * it so the trail stays a chain.
     */
    private static void assignTo(DAOFactory factory, TroubleTicket ticket, Long engineerId) {
        if (factory.getTroubleTicketDAO()
                .assignEngineer(ticket.getId(), engineerId, TicketStatus.OPEN)) {
            factory.getTicketStatusHistoryDAO().insert(new TicketStatusHistory(ticket.getId(),
                    TicketStatus.OPEN, TicketStatus.ASSIGNED, "vfy-sdesk",
                    "Assigned for verification"));
        }
    }

    /* ---------- Listeners used by the checks ---------- */

    private static final class RecordingListener implements TicketEventListener {

        private final String name;
        private final Set<TicketEventType> wanted;
        private TicketEvent last;

        private RecordingListener(String name, TicketEventType... wanted) {
            this.name = name;
            this.wanted = wanted.length == 0 ? null
                    : Collections.unmodifiableSet(EnumSet.copyOf(Arrays.asList(wanted)));
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isInterestedIn(TicketEventType type) {
            return wanted == null || wanted.contains(type);
        }

        @Override
        public void onTicketEvent(TicketEvent event) {
            this.last = event;
        }

        private String lastType() {
            return last == null ? "(nothing)" : last.getType().name();
        }
    }

    private static final class FailingListener implements TicketEventListener {

        @Override
        public String getName() {
            return "always-fails";
        }

        @Override
        public void onTicketEvent(TicketEvent event) {
            throw new IllegalStateException("this listener cannot do its job");
        }
    }

    /* ---------- Reading what was written ---------- */

    private static long countAudit(AuditLogDAO auditLog, TroubleTicket ticket) {
        return auditLog.findByEntity("TROUBLE_TICKET", ticket.getTicketNumber()).size();
    }

    private static AuditLog newestAudit(AuditLogDAO auditLog, TroubleTicket ticket) {
        List<AuditLog> rows = auditLog.findByEntity("TROUBLE_TICKET", ticket.getTicketNumber());
        if (rows.isEmpty()) {
            throw new IllegalStateException("No audit rows for " + ticket.getTicketNumber());
        }
        return rows.get(0);
    }

    /**
     * The actions recorded against a ticket, oldest first, which is the
     * order somebody reading the trail would want.
     */
    private static String actionsFor(AuditLogDAO auditLog, TroubleTicket ticket) {
        List<AuditLog> rows = auditLog.findByEntity("TROUBLE_TICKET", ticket.getTicketNumber());
        List<String> actions = new ArrayList<String>();
        for (int index = rows.size() - 1; index >= 0; index--) {
            actions.add(rows.get(index).getAction());
        }
        return actions.toString();
    }

    /**
     * Whether the trail runs newest to oldest, which is what the DAO
     * promises and what a console showing recent activity depends on.
     */
    private static boolean auditIsOrdered(AuditLogDAO auditLog, TroubleTicket ticket) {
        List<AuditLog> rows = auditLog.findByEntity("TROUBLE_TICKET", ticket.getTicketNumber());
        for (int index = 1; index < rows.size(); index++) {
            if (rows.get(index).getPerformedDate()
                    .isAfter(rows.get(index - 1).getPerformedDate())) {
                return false;
            }
        }
        return true;
    }

    private static boolean performersAreKnown(AuditLogDAO auditLog, TroubleTicket ticket) {
        for (AuditLog row : auditLog.findByEntity("TROUBLE_TICKET", ticket.getTicketNumber())) {
            if (row.getPerformedBy() == null || row.getPerformedBy().trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static Notification newestNotification(NotificationDAO inbox, TroubleTicket ticket) {
        List<Notification> rows = inbox.findByTicketId(ticket.getId());
        if (rows.isEmpty()) {
            throw new IllegalStateException("No notifications for " + ticket.getTicketNumber());
        }
        return rows.get(rows.size() - 1);
    }

    private static String newestMessage(NotificationDAO inbox, TroubleTicket ticket) {
        return newestNotification(inbox, ticket).getMessage();
    }

    private static String rolesTold(NotificationDAO inbox, TroubleTicket ticket) {
        List<String> roles = new ArrayList<String>();
        for (Notification sent : inbox.findByTicketId(ticket.getId())) {
            roles.add(sent.getRecipientRole().name());
        }
        return roles.toString();
    }

    private static List<Long> recipientsTold(NotificationDAO inbox, TroubleTicket ticket) {
        List<Long> recipients = new ArrayList<Long>();
        for (Notification sent : inbox.findByTicketId(ticket.getId())) {
            recipients.add(sent.getRecipientId());
        }
        return recipients;
    }

    private static String typesTold(NotificationDAO inbox, TroubleTicket ticket) {
        List<String> types = new ArrayList<String>();
        for (Notification sent : inbox.findByTicketId(ticket.getId())) {
            types.add(sent.getNotificationType().name());
        }
        return types.toString();
    }

    /**
     * The kinds of notification sent about a ticket, in the order the
     * notification types are declared, so repeats to several recipients
     * collapse to one entry.
     */
    private static String distinctTypesTold(NotificationDAO inbox, TroubleTicket ticket) {
        Set<NotificationType> types = EnumSet.noneOf(NotificationType.class);
        for (Notification sent : inbox.findByTicketId(ticket.getId())) {
            types.add(sent.getNotificationType());
        }
        return types.toString();
    }

    private static boolean allMessagesFit(NotificationDAO inbox, TroubleTicket ticket,
                                          int width) {
        for (Notification sent : inbox.findByTicketId(ticket.getId())) {
            if (sent.getMessage() == null || sent.getMessage().length() > width) {
                return false;
            }
        }
        return true;
    }

    private static boolean allAddressedTo(List<Notification> sent, Long userId) {
        for (Notification one : sent) {
            if (!userId.equals(one.getRecipientId())) {
                return false;
            }
        }
        return !sent.isEmpty();
    }

    private static boolean containsId(List<Notification> sent, Long notificationId) {
        for (Notification one : sent) {
            if (notificationId.equals(one.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes the notifications written about a ticket so the next check
     * starts from nothing. Inside the rolled back transaction, so the seeded
     * rows are never really touched.
     */
    private static void clearNotifications(NotificationDAO inbox, TroubleTicket ticket) {
        for (Notification sent : inbox.findByTicketId(ticket.getId())) {
            inbox.deleteById(sent.getId());
        }
    }

    /**
     * How many accounts in a role could actually read a message. Counted
     * from the database rather than assumed, because the seed's staff list
     * can be added to.
     */
    private static int reachable(UserDAO users, Role role) {
        int found = 0;
        for (UserAccount account : users.findByRole(role)) {
            if (account.getAccountStatus() != AccountStatus.DISABLED) {
                found++;
            }
        }
        return found;
    }

    /* ---------- Shared ---------- */

    private static TroubleTicket seeded(DAOFactory factory, String ticketNumber) {
        return factory.getTroubleTicketDAO().findByTicketNumber(ticketNumber)
                .orElseThrow(() -> new IllegalStateException(
                        "Seed ticket " + ticketNumber + " is missing"));
    }

    /**
     * A session for the first reachable account in a staff role, so the
     * checks run as somebody the database actually knows.
     */
    private static UserSession staffSession(DAOFactory factory, Role role) {
        for (UserAccount account : factory.getUserDAO().findByRole(role)) {
            if (account.getAccountStatus() != AccountStatus.DISABLED) {
                return sessionFor(account, null, null);
            }
        }
        throw new IllegalStateException("No " + role.name() + " account is seeded");
    }

    private static UserSession sessionFor(UserAccount account, Long customerId,
                                          Long engineerId) {
        return new UserSession(account, null, customerId, engineerId);
    }

    private static TroubleTicket raise(TicketService service, UserSession actor,
                                       TicketRequest request) {
        TroubleTicket raised = service.raise(actor, request);
        ISSUED.add(raised.getTicketNumber());
        return raised;
    }

    /** Java 8 has no {@code String.repeat}. */
    private static String repeat(String unit, int times) {
        StringBuilder builder = new StringBuilder(unit.length() * times);
        for (int index = 0; index < times; index++) {
            builder.append(unit);
        }
        return builder.toString();
    }

    private static int countIssuedTickets(TroubleTicketDAO tickets) {
        int found = 0;
        for (String number : ISSUED) {
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
    private static int removeIssuedTickets(TroubleTicketDAO tickets) {
        int removed = 0;
        for (String number : ISSUED) {
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
        System.out.println();
        System.out.println("  " + title);
    }

    private static void check(String label, String expected, String actual) {
        checksRun++;
        boolean passed = expected.equals(actual);
        if (!passed) {
            checksFailed++;
        }
        System.out.println(String.format("    [%s] %-46s expected=%-30s actual=%s",
                passed ? "PASS" : "FAIL", label, expected, actual));
    }
}
