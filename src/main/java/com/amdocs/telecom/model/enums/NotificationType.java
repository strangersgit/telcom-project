package com.amdocs.telecom.model.enums;

/**
 * The notification triggers listed in section 12 of the case study.
 *
 * <p>Section 12 names six: ticket creation, engineer assignment, SLA
 * warning, SLA breach, ticket resolution and ticket closure.
 * {@link #TICKET_ESCALATED} is a seventh, added here because section 9
 * hands a ticket up a four level ladder and the people it lands on have no
 * other way of learning that it did.</p>
 *
 * <p>Each constant carries a message template filled in by the notification
 * service, which keeps wording consistent across every channel.</p>
 */
public enum NotificationType implements DescribableEnum {

    TICKET_CREATED("NT01", "Ticket Created",
            "Ticket %s has been raised and is awaiting assignment."),
    ENGINEER_ASSIGNED("NT02", "Engineer Assigned",
            "Ticket %s has been assigned to engineer %s."),
    SLA_WARNING("NT03", "SLA Warning",
            "Ticket %s is approaching its SLA deadline. Time remaining: %s."),
    SLA_BREACH("NT04", "SLA Breach",
            "Ticket %s has breached its SLA deadline."),
    TICKET_ESCALATED("NT05", "Ticket Escalated",
            "Ticket %s has been escalated from %s to %s."),
    TICKET_RESOLVED("NT06", "Ticket Resolved",
            "Ticket %s has been resolved. Resolution code: %s."),
    TICKET_CLOSED("NT07", "Ticket Closed",
            "Ticket %s has been closed.");

    private final String code;
    private final String displayName;
    private final String messageTemplate;

    NotificationType(String code, String displayName, String messageTemplate) {
        this.code = code;
        this.displayName = displayName;
        this.messageTemplate = messageTemplate;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    public String getMessageTemplate() {
        return messageTemplate;
    }

    /**
     * Fills the template for this notification type.
     */
    public String format(Object... arguments) {
        return String.format(messageTemplate, arguments);
    }

    /**
     * SLA notifications are the ones the manager dashboard highlights.
     */
    public boolean isSlaAlert() {
        return this == SLA_WARNING || this == SLA_BREACH;
    }
}
