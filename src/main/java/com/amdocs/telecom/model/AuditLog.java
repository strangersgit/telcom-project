package com.amdocs.telecom.model;

import java.time.LocalDateTime;

/**
 * One line of the audit trail required by section 17 of the case study.
 *
 * <p>The entity is identified by type and business key rather than by a
 * foreign key, which is what lets an audit row outlive the record it
 * describes. The action is a free string because rows arrive from two
 * places: the Java services, and the database trigger on engineer
 * availability.</p>
 */
public class AuditLog extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String entityType;
    private String entityId;
    private String action;
    private String performedBy;
    private LocalDateTime performedDate;
    private String oldValue;
    private String newValue;
    private String details;

    public AuditLog() {
        super();
    }

    public AuditLog(String entityType, String entityId, String action, String performedBy) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.performedBy = performedBy;
        this.performedDate = stampNow();
    }

    /**
     * Builds an entry for any auditable entity without the caller needing to
     * know its concrete type.
     */
    public static AuditLog forEntity(Auditable entity, String action, String performedBy) {
        return new AuditLog(entity.getAuditEntityType(), entity.getAuditEntityId(), action, performedBy);
    }

    /**
     * Records a field moving from one value to another.
     */
    public AuditLog withChange(String from, String to) {
        this.oldValue = from;
        this.newValue = to;
        return this;
    }

    public AuditLog withDetails(String text) {
        this.details = text;
        return this;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getPerformedBy() {
        return performedBy;
    }

    public void setPerformedBy(String performedBy) {
        this.performedBy = performedBy;
    }

    public LocalDateTime getPerformedDate() {
        return performedDate;
    }

    public void setPerformedDate(LocalDateTime performedDate) {
        this.performedDate = performedDate;
    }

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    /**
     * Distinguishes the rows written by the database trigger from those the
     * application wrote.
     */
    public boolean isSystemGenerated() {
        return "DB_TRIGGER".equals(performedBy) || "SYSTEM".equals(performedBy);
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-18s %-18s %-14s %-22s %s",
                Displayable.formatDateTime(performedDate),
                Displayable.truncate(entityType, 18),
                Displayable.truncate(entityId, 14),
                Displayable.truncate(action, 22),
                Displayable.truncate(performedBy, 16));
    }

    @Override
    public String toDetailBlock() {
        StringBuilder builder = new StringBuilder();
        builder.append(Displayable.labelled("When",
                Displayable.formatDateTime(performedDate))).append(System.lineSeparator());
        builder.append(Displayable.labelled("Entity", entityType + " " + Displayable.orDash(entityId)))
                .append(System.lineSeparator());
        builder.append(Displayable.labelled("Action", action)).append(System.lineSeparator());
        builder.append(Displayable.labelled("By", performedBy)).append(System.lineSeparator());
        builder.append(Displayable.labelled("From", oldValue)).append(System.lineSeparator());
        builder.append(Displayable.labelled("To", newValue)).append(System.lineSeparator());
        builder.append(Displayable.labelled("Details", details));
        return builder.toString();
    }
}
