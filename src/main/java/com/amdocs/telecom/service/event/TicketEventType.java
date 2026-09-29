package com.amdocs.telecom.service.event;

import com.amdocs.telecom.model.enums.NotificationType;

import java.util.Optional;

/**
 * Everything that can happen to a ticket and be worth telling somebody
 * about.
 *
 * <p>Two things hang off each constant. The first is the action string
 * written into {@code audit_log.action}, which section 17 requires for
 * every change. The second is the {@link NotificationType} to send, or
 * {@code null} where section 12 asks for no notification.</p>
 *
 * <p>Holding both here means the list of things that notify somebody is
 * visible in one place and can be checked against section 12 rather than
 * scattered through a switch. The constants that carry a notification type
 * are exactly section 12's six triggers plus escalation, which
 * {@link NotificationType} explains.</p>
 */
public enum TicketEventType {

    /* ---------- Raising and assignment ---------- */

    RAISED("TICKET_RAISED", NotificationType.TICKET_CREATED),

    ASSIGNED("ENGINEER_ASSIGNED", NotificationType.ENGINEER_ASSIGNED),

    /* ---------- Working the ticket ---------- */

    /**
     * A move to IN_PROGRESS or PENDING_CUSTOMER. The moves that mean
     * something more specific have their own constant below.
     */
    STATUS_CHANGED("STATUS_CHANGED", null),

    PRIORITY_CHANGED("PRIORITY_CHANGED", null),

    DIAGNOSIS_RECORDED("DIAGNOSIS_RECORDED", null),

    /**
     * The first time anybody worked on the ticket, which is the measurement
     * the response SLA is judged against.
     */
    FIRST_RESPONSE("FIRST_RESPONSE", null),

    /* ---------- Settling the ticket ---------- */

    RESOLVED("TICKET_RESOLVED", NotificationType.TICKET_RESOLVED),

    CLOSED("TICKET_CLOSED", NotificationType.TICKET_CLOSED),

    /**
     * Cancellation notifies nobody. Section 12 does not ask for it, and the
     * person who cancels a ticket is either the customer who raised it or
     * the operator talking to them, so both already know.
     */
    CANCELLED("TICKET_CANCELLED", null),

    /**
     * Reopening notifies nobody for the same reason: whoever reopens is in
     * the conversation. The engineer learns of it through their own queue,
     * which the ticket rejoins.
     */
    REOPENED("TICKET_REOPENED", null),

    /* ---------- Oversight ---------- */

    ESCALATED("TICKET_ESCALATED", NotificationType.TICKET_ESCALATED),

    SLA_AT_RISK("SLA_AT_RISK", NotificationType.SLA_WARNING),

    SLA_BREACHED("SLA_BREACHED", NotificationType.SLA_BREACH),

    /**
     * Feedback is recorded but announced to nobody. Section 11 collects it
     * for the satisfaction report in section 16, not to interrupt anyone.
     */
    FEEDBACK_SUBMITTED("FEEDBACK_SUBMITTED", null);

    private final String auditAction;
    private final NotificationType notificationType;

    TicketEventType(String auditAction, NotificationType notificationType) {
        this.auditAction = auditAction;
        this.notificationType = notificationType;
    }

    /**
     * The value written to {@code audit_log.action}.
     */
    public String getAuditAction() {
        return auditAction;
    }

    /**
     * The notification to send, empty where nobody is told.
     */
    public Optional<NotificationType> findNotificationType() {
        return Optional.ofNullable(notificationType);
    }

    /**
     * Whether this event reaches somebody's inbox.
     */
    public boolean notifies() {
        return notificationType != null;
    }
}
