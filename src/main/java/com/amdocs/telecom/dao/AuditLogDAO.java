package com.amdocs.telecom.dao;

import com.amdocs.telecom.model.AuditLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The audit trail from section 17. Write once and read; there is no update
 * or delete path, deliberately.
 */
public interface AuditLogDAO extends GenericDAO<AuditLog, Long> {

    /**
     * Everything recorded about one record, newest first.
     */
    List<AuditLog> findByEntity(String entityType, String entityId);

    List<AuditLog> findByUser(String performedBy, int limit);

    List<AuditLog> findBetween(LocalDateTime from, LocalDateTime to);

    List<AuditLog> findRecent(int limit);

    int insertBatch(List<AuditLog> entries);
}
