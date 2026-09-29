package com.amdocs.telecom.dao.impl;

import com.amdocs.telecom.dao.EscalationHistoryDAO;
import com.amdocs.telecom.dao.RowMapper;
import com.amdocs.telecom.model.EscalationHistory;
import com.amdocs.telecom.model.enums.EscalationLevel;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * JDBC access to the {@code escalation_history} table.
 */
public class EscalationHistoryDAOImpl extends AppendOnlyJdbcDAO<EscalationHistory>
        implements EscalationHistoryDAO {

    private static final String INSERT_SQL =
            "INSERT INTO escalation_history (ticket_id, from_level, to_level, reason, "
                    + "escalation_date, escalated_by, auto_escalated) VALUES (?, ?, ?, ?, ?, ?, ?)";

    @Override
    public String tableName() {
        return "escalation_history";
    }

    @Override
    protected String idColumn() {
        return "escalation_id";
    }

    @Override
    protected RowMapper<EscalationHistory> mapper() {
        return EscalationHistoryDAOImpl::mapRow;
    }

    static EscalationHistory mapRow(ResultSet resultSet) throws SQLException {
        EscalationHistory escalation = new EscalationHistory();
        escalation.setId(resultSet.getLong("escalation_id"));
        escalation.setTicketId(resultSet.getLong("ticket_id"));
        escalation.setFromLevel(JdbcSupport.enumValue(resultSet, "from_level", EscalationLevel.class));
        escalation.setToLevel(JdbcSupport.enumValue(resultSet, "to_level", EscalationLevel.class));
        escalation.setReason(resultSet.getString("reason"));
        escalation.setEscalationDate(JdbcSupport.localDateTime(resultSet, "escalation_date"));
        escalation.setEscalatedBy(resultSet.getString("escalated_by"));
        escalation.setAutoEscalated(resultSet.getBoolean("auto_escalated"));
        return escalation;
    }

    @Override
    protected String insertSql() {
        return INSERT_SQL;
    }

    @Override
    protected Object[] insertParameters(EscalationHistory escalation) {
        return new Object[]{
                escalation.getTicketId(),
                escalation.getFromLevel(),
                escalation.getToLevel(),
                escalation.getReason(),
                escalation.getEscalationDate() == null
                        ? LocalDateTime.now() : escalation.getEscalationDate(),
                escalation.getEscalatedBy(),
                escalation.isAutoEscalated()
        };
    }

    @Override
    public List<EscalationHistory> findByTicketId(Long ticketId) {
        return query("SELECT * FROM escalation_history WHERE ticket_id = ? "
                + "ORDER BY escalation_date ASC, escalation_id ASC", ticketId);
    }

    @Override
    public List<EscalationHistory> findBetween(LocalDateTime from, LocalDateTime to) {
        return query("SELECT * FROM escalation_history WHERE escalation_date BETWEEN ? AND ? "
                + "ORDER BY escalation_date DESC, escalation_id DESC", from, to);
    }

    @Override
    public List<EscalationHistory> findAutoEscalated() {
        return query("SELECT * FROM escalation_history WHERE auto_escalated = TRUE "
                + "ORDER BY escalation_date DESC, escalation_id DESC");
    }

    @Override
    public long countByTicketId(Long ticketId) {
        return queryLong("SELECT COUNT(*) FROM escalation_history WHERE ticket_id = ?", ticketId)
                .orElse(0L);
    }
}
