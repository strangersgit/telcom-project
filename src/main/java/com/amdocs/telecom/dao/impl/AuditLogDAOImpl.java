package com.amdocs.telecom.dao.impl;

import com.amdocs.telecom.dao.AuditLogDAO;
import com.amdocs.telecom.dao.RowMapper;
import com.amdocs.telecom.model.AuditLog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC access to the {@code audit_log} table.
 *
 * <p>Rows arrive from two directions, the services and the database trigger
 * on engineer availability, and both are read back through here.</p>
 */
public class AuditLogDAOImpl extends AppendOnlyJdbcDAO<AuditLog> implements AuditLogDAO {

    private static final String INSERT_SQL =
            "INSERT INTO audit_log (entity_type, entity_id, action, performed_by, performed_date, "
                    + "old_value, new_value, details) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    @Override
    public String tableName() {
        return "audit_log";
    }

    @Override
    protected String idColumn() {
        return "audit_id";
    }

    @Override
    protected String defaultOrderBy() {
        return "performed_date DESC";
    }

    @Override
    protected RowMapper<AuditLog> mapper() {
        return AuditLogDAOImpl::mapRow;
    }

    static AuditLog mapRow(ResultSet resultSet) throws SQLException {
        AuditLog entry = new AuditLog();
        entry.setId(resultSet.getLong("audit_id"));
        entry.setEntityType(resultSet.getString("entity_type"));
        entry.setEntityId(resultSet.getString("entity_id"));
        entry.setAction(resultSet.getString("action"));
        entry.setPerformedBy(resultSet.getString("performed_by"));
        entry.setPerformedDate(JdbcSupport.localDateTime(resultSet, "performed_date"));
        entry.setOldValue(resultSet.getString("old_value"));
        entry.setNewValue(resultSet.getString("new_value"));
        entry.setDetails(resultSet.getString("details"));
        return entry;
    }

    @Override
    protected String insertSql() {
        return INSERT_SQL;
    }

    @Override
    protected Object[] insertParameters(AuditLog entry) {
        return new Object[]{
                entry.getEntityType(),
                entry.getEntityId(),
                entry.getAction(),
                entry.getPerformedBy(),
                entry.getPerformedDate() == null ? LocalDateTime.now() : entry.getPerformedDate(),
                entry.getOldValue(),
                entry.getNewValue(),
                entry.getDetails()
        };
    }

    @Override
    public List<AuditLog> findByEntity(String entityType, String entityId) {
        return query("SELECT * FROM audit_log WHERE entity_type = ? AND entity_id = ? "
                + "ORDER BY performed_date DESC, audit_id DESC", entityType, entityId);
    }

    @Override
    public List<AuditLog> findByUser(String performedBy, int limit) {
        return query("SELECT * FROM audit_log WHERE performed_by = ? "
                + "ORDER BY performed_date DESC, audit_id DESC LIMIT ?", performedBy, limit);
    }

    @Override
    public List<AuditLog> findBetween(LocalDateTime from, LocalDateTime to) {
        return query("SELECT * FROM audit_log WHERE performed_date BETWEEN ? AND ? "
                + "ORDER BY performed_date DESC, audit_id DESC", from, to);
    }

    @Override
    public List<AuditLog> findRecent(int limit) {
        return query("SELECT * FROM audit_log ORDER BY performed_date DESC, audit_id DESC LIMIT ?",
                limit);
    }

    @Override
    public int insertBatch(List<AuditLog> entries) {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        List<Object[]> rows = new ArrayList<>(entries.size());
        for (AuditLog entry : entries) {
            rows.add(insertParameters(entry));
        }
        return countBatchWrites(executeBatch(INSERT_SQL, rows));
    }
}
