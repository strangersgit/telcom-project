package com.amdocs.telecom.dao;

import com.amdocs.telecom.model.Notification;
import com.amdocs.telecom.model.enums.NotificationType;

import java.util.List;

/**
 * Queued messages produced by the triggers in section 12.
 */
public interface NotificationDAO extends GenericDAO<Notification, Long> {

    /**
     * A recipient's inbox, newest first.
     */
    List<Notification> findByRecipient(Long recipientId, boolean unreadOnly, int limit);

    List<Notification> findByTicketId(Long ticketId);

    List<Notification> findByType(NotificationType type);

    long countUnread(Long recipientId);

    /**
     * Messages waiting to be delivered, oldest first, across every
     * recipient.
     *
     * <p>Waiting means unread. The table records whether a recipient has
     * read a message, not whether the dispatcher has delivered it, so this
     * is the closest the schema comes to a pending set: a message somebody
     * has already read plainly needs no delivering. The dispatcher in
     * {@link com.amdocs.telecom.scheduler.NotificationProcessor} keeps its
     * own record of what it has sent so it does not send the same message
     * twice in one run.</p>
     */
    List<Notification> findPendingDispatch(int limit);

    /**
     * Marks one message read. The database trigger stamps the read time, so
     * a row updated by any route stays consistent.
     */
    boolean markAsRead(Long notificationId);

    /**
     * Clears a whole inbox at once.
     *
     * @return how many messages were marked read
     */
    int markAllAsRead(Long recipientId);

    /**
     * Writes many messages in one round trip, which is how the SLA monitor
     * raises alerts for a batch of tickets.
     */
    int insertBatch(List<Notification> notifications);
}
