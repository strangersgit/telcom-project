package com.amdocs.telecom.service;

import com.amdocs.telecom.model.Notification;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.event.TicketEvent;

import java.util.List;

/**
 * Section 12's notifications: the writing of them, and the inbox the
 * console reads them from.
 *
 * <p>The write side takes a {@link TicketEvent} rather than a recipient and
 * a message. Deciding who should hear about a resolved ticket is a business
 * rule, and it belongs in one place rather than at every call site that
 * resolves a ticket. The implementation is also the event listener, so in
 * practice nothing calls {@link #publish} directly; it is public so the
 * routing can be exercised on its own.</p>
 *
 * <p>The read side is always the caller's own inbox. There is no method
 * that takes a recipient, which means there is no way to read somebody
 * else's notifications, by accident or otherwise. {@link #forTicket} is the
 * one exception and asks for the audit permission, because it is an
 * oversight view rather than an inbox.</p>
 */
public interface NotificationService {

    /**
     * Works out who should hear about an event and writes their
     * notifications.
     *
     * @return how many notifications were written, which is zero for the
     *         event types section 12 does not ask about
     */
    int publish(TicketEvent event);

    /**
     * The caller's own notifications, newest first.
     *
     * @param unreadOnly true to leave out the ones already read
     * @param limit      how many to return at most
     */
    List<Notification> inbox(UserSession actor, boolean unreadOnly, int limit);

    /**
     * How many of the caller's notifications are still unread, for the
     * badge on the dashboard.
     */
    long unreadCount(UserSession actor);

    /**
     * Marks one of the caller's own notifications read.
     *
     * @return false if it was already read, or does not belong to the
     *         caller
     */
    boolean markRead(UserSession actor, Long notificationId);

    /**
     * Marks every unread notification of the caller's read.
     *
     * @return how many were still unread
     */
    int markAllRead(UserSession actor);

    /**
     * Everything sent about one ticket, oldest first. An oversight view for
     * the network manager, not an inbox.
     */
    List<Notification> forTicket(UserSession actor, String ticketNumber);
}
