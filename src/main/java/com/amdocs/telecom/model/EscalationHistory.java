package com.amdocs.telecom.model;

import com.amdocs.telecom.model.enums.EscalationLevel;

import java.time.LocalDateTime;

/**
 * One step up the four level escalation ladder described in section 10 of
 * the case study.
 *
 * <p>The {@code autoEscalated} flag separates the escalations the SLA monitor
 * raised on its own from those a person triggered, which the escalation
 * report breaks out.</p>
 */
public class EscalationHistory extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long ticketId;
    private EscalationLevel fromLevel;
    private EscalationLevel toLevel;
    private String reason;
    private LocalDateTime escalationDate;
    private String escalatedBy;
    private boolean autoEscalated;

    public EscalationHistory() {
        super();
    }

    public EscalationHistory(Long ticketId, EscalationLevel fromLevel, EscalationLevel toLevel,
                             String reason, String escalatedBy, boolean autoEscalated) {
        this.ticketId = ticketId;
        this.fromLevel = fromLevel;
        this.toLevel = toLevel;
        this.reason = reason;
        this.escalatedBy = escalatedBy;
        this.autoEscalated = autoEscalated;
        this.escalationDate = stampNow();
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public EscalationLevel getFromLevel() {
        return fromLevel;
    }

    public void setFromLevel(EscalationLevel fromLevel) {
        this.fromLevel = fromLevel;
    }

    public EscalationLevel getToLevel() {
        return toLevel;
    }

    public void setToLevel(EscalationLevel toLevel) {
        this.toLevel = toLevel;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public LocalDateTime getEscalationDate() {
        return escalationDate;
    }

    public void setEscalationDate(LocalDateTime escalationDate) {
        this.escalationDate = escalationDate;
    }

    public String getEscalatedBy() {
        return escalatedBy;
    }

    public void setEscalatedBy(String escalatedBy) {
        this.escalatedBy = escalatedBy;
    }

    public boolean isAutoEscalated() {
        return autoEscalated;
    }

    public void setAutoEscalated(boolean autoEscalated) {
        this.autoEscalated = autoEscalated;
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-18s %-18s -> %-18s %-10s %s",
                Displayable.formatDateTime(escalationDate),
                fromLevel == null ? "-" : fromLevel.getDisplayName(),
                toLevel == null ? "-" : toLevel.getDisplayName(),
                autoEscalated ? "auto" : "manual",
                Displayable.truncate(reason, 40));
    }
}
