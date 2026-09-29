package com.amdocs.telecom.dao.impl;

import com.amdocs.telecom.dao.NotificationDAO;
import com.amdocs.telecom.dao.RowMapper;
import com.amdocs.telecom.model.Notification;
import com.amdocs.telecom.model.enums.NotificationType;
import com.amdocs.telecom.model.enums.Role;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC access to the {@code notifications} table.
 */
public class NotificationDAOImpl extends AbstractJdbcDAO<Notification> implements NotificationDAO {

    private static final String INSERT_SQL =
            "INSERT INTO notifications (recipient_id, recipient_role, notification_type, message, "
                    + "ticket_id, read_status, created_date) VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE notifications SET message = ?, read_status = ? WHERE notification_id = ?";

    @Override
    public String tableName() {
        return "notifications";
    }

    @Override
    protected String idColumn() {
        return "notification_id";
    }

    @Override
    protected String defaultOrderBy() {
        return "created_date DESC";
    }

    @Override
    protected RowMapper<Notification> mapper() {
        return NotificationDAOImpl::mapRow;
    }

    static Notification mapRow(ResultSet resultSet) throws SQLException {
        Notification notification = new Notification();
        notification.setId(resultSet.getLong("notification_id"));
        notification.setRecipientId(resultSet.getLong("recipient_id"));
        notification.setRecipientRole(JdbcSupport.enumValue(resultSet, "recipient_role", Role.class));
        notification.setNotificationType(
                JdbcSupport.enumValue(resultSet, "notification_type", NotificationType.class));
        notification.setMessage(resultSet.getString("message"));
        notification.setTicketId(JdbcSupport.nullableLong(resultSet, "ticket_id"));
        notification.setReadStatus(resultSet.getBoolean("read_status"));
        notification.setCreatedDate(JdbcSupport.localDateTime(resultSet, "created_date"));
        notification.setReadDate(JdbcSupport.localDateTime(resultSet, "read_date"));
        return notification;
    }

    @Override
    protected String insertSql() {
        return INSERT_SQL;
    }

    @Override
    protected Object[] insertParameters(Notification notification) {
        return new Object[]{
                notification.getRecipientId(),
                notification.getRecipientRole(),
                notification.getNotificationType(),
                notification.getMessage(),
                notification.getTicketId(),
                notification.isReadStatus(),
                notification.getCreatedDate() == null
                        ? LocalDateTime.now() : notification.getCreatedDate()
        };
    }

    /**
     * {@code read_date} is absent because the
     * {@code trg_notifications_before_update} trigger stamps it whenever
     * {@code read_status} turns true.
     */
    @Override
    protected String updateSql() {
        return UPDATE_SQL;
    }

    @Override
    protected Object[] updateParameters(Notification notification) {
        return new Object[]{
                notification.getMessage(),
                notification.isReadStatus(),
                notification.getId()
        };
    }

    /* ---------- Inbox ---------- */

    @Override
    public List<Notification> findByRecipient(Long recipientId, boolean unreadOnly, int limit) {
        String sql = "SELECT * FROM notifications WHERE recipient_id = ?"
                + (unreadOnly ? " AND read_status = FALSE" : "")
                + " ORDER BY created_date DESC, notification_id DESC LIMIT ?";
        return query(sql, recipientId, limit);
    }

    @Override
    public List<Notification> findByTicketId(Long ticketId) {
        return query("SELECT * FROM notifications WHERE ticket_id = ? "
                        + "ORDER BY created_date ASC, notification_id ASC",
                ticketId);
    }

    @Override
    public List<Notification> findByType(NotificationType type) {
        return query("SELECT * FROM notifications WHERE notification_type = ? "
                + "ORDER BY created_date DESC, notification_id DESC", type);
    }

    @Override
    public long countUnread(Long recipientId) {
        return queryLong("SELECT COUNT(*) FROM notifications "
                + "WHERE recipient_id = ? AND read_status = FALSE", recipientId).orElse(0L);
    }

    /**
     * Oldest first, with the key breaking ties, so a dispatcher walking this
     * list twice sees the same order both times.
     */
    @Override
    public List<Notification> findPendingDispatch(int limit) {
        return query("SELECT * FROM notifications WHERE read_status = FALSE "
                + "ORDER BY created_date ASC, notification_id ASC LIMIT ?", limit);
    }

    @Override
    public boolean markAsRead(Long notificationId) {
        return executeUpdate("UPDATE notifications SET read_status = TRUE "
                + "WHERE notification_id = ? AND read_status = FALSE", notificationId) > 0;
    }

    @Override
    public int markAllAsRead(Long recipientId) {
        return executeUpdate("UPDATE notifications SET read_status = TRUE "
                + "WHERE recipient_id = ? AND read_status = FALSE", recipientId);
    }

    /**
     * The SLA monitor can find a dozen tickets at risk in one sweep, so the
     * alerts go out as one batch rather than a round trip each.
     */
    @Override
    public int insertBatch(List<Notification> notifications) {
        if (notifications == null || notifications.isEmpty()) {
            return 0;
        }
        List<Object[]> rows = new ArrayList<>(notifications.size());
        for (Notification notification : notifications) {
            rows.add(insertParameters(notification));
        }
        return countBatchWrites(executeBatch(INSERT_SQL, rows));
    }
}
