package com.amdocs.telecom.dao.impl;

import com.amdocs.telecom.dao.RowMapper;
import com.amdocs.telecom.dao.TicketStatusHistoryDAO;
import com.amdocs.telecom.model.TicketStatusHistory;
import com.amdocs.telecom.model.enums.TicketStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * JDBC access to the {@code ticket_status_history} table.
 */
public class TicketStatusHistoryDAOImpl extends AppendOnlyJdbcDAO<TicketStatusHistory>
        implements TicketStatusHistoryDAO {

    private static final String INSERT_SQL =
            "INSERT INTO ticket_status_history (ticket_id, old_status, new_status, changed_by, "
                    + "changed_date, remarks) VALUES (?, ?, ?, ?, ?, ?)";

    @Override
    public String tableName() {
        return "ticket_status_history";
    }

    @Override
    protected String idColumn() {
        return "history_id";
    }

    @Override
    protected RowMapper<TicketStatusHistory> mapper() {
        return TicketStatusHistoryDAOImpl::mapRow;
    }

    static TicketStatusHistory mapRow(ResultSet resultSet) throws SQLException {
        TicketStatusHistory history = new TicketStatusHistory();
        history.setId(resultSet.getLong("history_id"));
        history.setTicketId(resultSet.getLong("ticket_id"));
        history.setOldStatus(JdbcSupport.enumValue(resultSet, "old_status", TicketStatus.class));
        history.setNewStatus(JdbcSupport.enumValue(resultSet, "new_status", TicketStatus.class));
        history.setChangedBy(resultSet.getString("changed_by"));
        history.setChangedDate(JdbcSupport.localDateTime(resultSet, "changed_date"));
        history.setRemarks(resultSet.getString("remarks"));
        return history;
    }

    @Override
    protected String insertSql() {
        return INSERT_SQL;
    }

    @Override
    protected Object[] insertParameters(TicketStatusHistory history) {
        return new Object[]{
                history.getTicketId(),
                history.getOldStatus(),
                history.getNewStatus(),
                history.getChangedBy(),
                history.getChangedDate() == null ? LocalDateTime.now() : history.getChangedDate(),
                history.getRemarks()
        };
    }

    @Override
    public List<TicketStatusHistory> findByTicketId(Long ticketId) {
        return query("SELECT * FROM ticket_status_history WHERE ticket_id = ? "
                + "ORDER BY changed_date ASC, history_id ASC", ticketId);
    }

    @Override
    public List<TicketStatusHistory> findRecent(int limit) {
        return query("SELECT * FROM ticket_status_history ORDER BY changed_date DESC, "
                + "history_id DESC LIMIT ?", limit);
    }

    @Override
    public long countByTicketId(Long ticketId) {
        return queryLong("SELECT COUNT(*) FROM ticket_status_history WHERE ticket_id = ?", ticketId)
                .orElse(0L);
    }
}
