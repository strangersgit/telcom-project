package com.amdocs.telecom.model;

import com.amdocs.telecom.model.enums.NotificationType;
import com.amdocs.telecom.model.enums.Role;

import java.time.LocalDateTime;

/**
 * A message queued for a user, produced by one of the triggers in section 12
 * of the case study.
 *
 * <p>The message text is rendered from the type's template when the
 * notification is built rather than when it is read, so the wording stays
 * fixed even if the underlying ticket moves on.</p>
 */
public class Notification extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long recipientId;
    private Role recipientRole;
    private NotificationType notificationType;
    private String message;
    private Long ticketId;
    private boolean readStatus;
    private LocalDateTime createdDate;
    private LocalDateTime readDate;

    public Notification() {
        super();
    }

    public Notification(Long recipientId, Role recipientRole, NotificationType notificationType,
                        String message, Long ticketId) {
        this.recipientId = recipientId;
        this.recipientRole = recipientRole;
        this.notificationType = notificationType;
        this.message = message;
        this.ticketId = ticketId;
        this.createdDate = stampNow();
    }

    public Long getRecipientId() {
        return recipientId;
    }

    public void setRecipientId(Long recipientId) {
        this.recipientId = recipientId;
    }

    public Role getRecipientRole() {
        return recipientRole;
    }

    public void setRecipientRole(Role recipientRole) {
        this.recipientRole = recipientRole;
    }

    public NotificationType getNotificationType() {
        return notificationType;
    }

    public void setNotificationType(NotificationType notificationType) {
        this.notificationType = notificationType;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public boolean isReadStatus() {
        return readStatus;
    }

    public void setReadStatus(boolean readStatus) {
        this.readStatus = readStatus;
    }

    public LocalDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDateTime createdDate) {
        this.createdDate = createdDate;
    }

    public LocalDateTime getReadDate() {
        return readDate;
    }

    public void setReadDate(LocalDateTime readDate) {
        this.readDate = readDate;
    }

    public boolean isUnread() {
        return !readStatus;
    }

    /**
     * Marks the notification read in memory. The database trigger stamps
     * {@code read_date} independently, so a row updated by any route ends up
     * consistent.
     */
    public void markAsRead() {
        if (!readStatus) {
            this.readStatus = true;
            this.readDate = stampNow();
        }
    }

    public boolean isSlaAlert() {
        return notificationType != null && notificationType.isSlaAlert();
    }

    @Override
    public String toSummaryLine() {
        return String.format("%s %-18s %-20s %s",
                readStatus ? "   " : " * ",
                Displayable.formatDateTime(createdDate),
                notificationType == null ? "-" : notificationType.getDisplayName(),
                Displayable.truncate(message, 60));
    }
}
