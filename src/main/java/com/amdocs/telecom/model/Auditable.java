package com.amdocs.telecom.model;

/**
 * Implemented by entities whose changes must reach the audit trail.
 *
 * <p>The audit service writes rows without knowing the concrete type: it asks
 * the entity what it is and which record it represents, which keeps a single
 * audit path working for tickets, engineers, customers and accounts alike.</p>
 */
public interface Auditable {

    /**
     * Logical type name recorded in {@code audit_log.entity_type}.
     */
    String getAuditEntityType();

    /**
     * Business identifier recorded in {@code audit_log.entity_id}, preferred
     * over the surrogate key so the trail stays readable.
     */
    String getAuditEntityId();

    /**
     * Short description used when an audit row needs no further detail.
     */
    default String describeForAudit() {
        return getAuditEntityType() + " [" + getAuditEntityId() + "]";
    }
}
