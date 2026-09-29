package com.amdocs.telecom.dao.impl;

import com.amdocs.telecom.dao.NetworkEventDAO;
import com.amdocs.telecom.dao.RowMapper;
import com.amdocs.telecom.model.NetworkEvent;
import com.amdocs.telecom.model.enums.EventStatus;
import com.amdocs.telecom.model.enums.NetworkEventType;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.model.enums.Severity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC access to the {@code network_events} table.
 */
public class NetworkEventDAOImpl extends AbstractJdbcDAO<NetworkEvent> implements NetworkEventDAO {

    private static final String INSERT_SQL =
            "INSERT INTO network_events (event_reference, network_node, event_type, severity, "
                    + "event_time, event_status, region, details, ticket_id, processed_date) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE network_events SET network_node = ?, event_type = ?, severity = ?, "
                    + "event_time = ?, event_status = ?, region = ?, details = ?, ticket_id = ?, "
                    + "processed_date = ? WHERE event_id = ?";

    @Override
    public String tableName() {
        return "network_events";
    }

    @Override
    protected String idColumn() {
        return "event_id";
    }

    @Override
    protected String defaultOrderBy() {
        return "event_time DESC";
    }

    @Override
    protected RowMapper<NetworkEvent> mapper() {
        return NetworkEventDAOImpl::mapRow;
    }

    static NetworkEvent mapRow(ResultSet resultSet) throws SQLException {
        NetworkEvent event = new NetworkEvent();
        event.setId(resultSet.getLong("event_id"));
        event.setEventReference(resultSet.getString("event_reference"));
        event.setNetworkNode(resultSet.getString("network_node"));
        event.setEventType(JdbcSupport.enumValue(resultSet, "event_type", NetworkEventType.class));
        event.setSeverity(JdbcSupport.enumValue(resultSet, "severity", Severity.class));
        event.setEventTime(JdbcSupport.localDateTime(resultSet, "event_time"));
        event.setEventStatus(JdbcSupport.enumValue(resultSet, "event_status", EventStatus.class));
        event.setRegion(JdbcSupport.enumValue(resultSet, "region", Region.class));
        event.setDetails(resultSet.getString("details"));
        event.setTicketId(JdbcSupport.nullableLong(resultSet, "ticket_id"));
        event.setProcessedDate(JdbcSupport.localDateTime(resultSet, "processed_date"));
        event.setCreatedAt(JdbcSupport.localDateTime(resultSet, "created_at"));
        return event;
    }

    @Override
    protected String insertSql() {
        return INSERT_SQL;
    }

    @Override
    protected Object[] insertParameters(NetworkEvent event) {
        return new Object[]{
                event.getEventReference(),
                event.getNetworkNode(),
                event.getEventType(),
                event.getSeverity(),
                event.getEventTime() == null ? LocalDateTime.now() : event.getEventTime(),
                event.getEventStatus(),
                event.getRegion(),
                event.getDetails(),
                event.getTicketId(),
                event.getProcessedDate()
        };
    }

    @Override
    protected String updateSql() {
        return UPDATE_SQL;
    }

    @Override
    protected Object[] updateParameters(NetworkEvent event) {
        return new Object[]{
                event.getNetworkNode(),
                event.getEventType(),
                event.getSeverity(),
                event.getEventTime(),
                event.getEventStatus(),
                event.getRegion(),
                event.getDetails(),
                event.getTicketId(),
                event.getProcessedDate(),
                event.getId()
        };
    }

    /* ---------- Finders ---------- */

    @Override
    public Optional<NetworkEvent> findByReference(String eventReference) {
        return queryOne("SELECT * FROM network_events WHERE event_reference = ?", eventReference);
    }

    @Override
    public List<NetworkEvent> findByStatus(EventStatus status) {
        return query("SELECT * FROM network_events WHERE event_status = ? ORDER BY event_time DESC",
                status);
    }

    @Override
    public List<NetworkEvent> findByType(NetworkEventType eventType) {
        return query("SELECT * FROM network_events WHERE event_type = ? ORDER BY event_time DESC",
                eventType);
    }

    @Override
    public List<NetworkEvent> findBySeverity(Severity severity) {
        return query("SELECT * FROM network_events WHERE severity = ? ORDER BY event_time DESC",
                severity);
    }

    @Override
    public List<NetworkEvent> findByNode(String networkNode) {
        return query("SELECT * FROM network_events WHERE network_node = ? ORDER BY event_time DESC",
                networkNode);
    }

    /**
     * Oldest first, because an alarm that has been waiting longest is the
     * one most worth looking at.
     */
    @Override
    public List<NetworkEvent> findPending(int limit) {
        return query("SELECT * FROM network_events WHERE event_status = ? "
                + "ORDER BY event_time ASC LIMIT ?", EventStatus.RECEIVED, limit);
    }

    /* ---------- Processing ---------- */

    /**
     * The status guard is what makes this safe to call from several consumer
     * threads at once. Exactly one of them changes a row and gets true back;
     * the rest see false and move on, so an alarm is never turned into two
     * tickets.
     */
    @Override
    public boolean claimForProcessing(Long eventId, EventStatus expectedStatus) {
        return executeUpdate("UPDATE network_events SET event_status = ? "
                        + "WHERE event_id = ? AND event_status = ?",
                EventStatus.PROCESSING, eventId, expectedStatus) > 0;
    }

    @Override
    public boolean markProcessed(Long eventId, EventStatus outcome, Long ticketId) {
        return executeUpdate("UPDATE network_events SET event_status = ?, ticket_id = ?, "
                        + "processed_date = NOW() WHERE event_id = ?",
                outcome, ticketId, eventId) > 0;
    }

    @Override
    public List<NetworkEvent> findBetween(LocalDateTime from, LocalDateTime to) {
        return query("SELECT * FROM network_events WHERE event_time BETWEEN ? AND ? "
                + "ORDER BY event_time DESC", from, to);
    }

    /**
     * One round trip for the whole burst, which matters because the
     * simulator in section 11 produces alarms far faster than one insert
     * each would allow.
     */
    @Override
    public int insertBatch(List<NetworkEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        List<Object[]> rows = new ArrayList<>(events.size());
        for (NetworkEvent event : events) {
            rows.add(insertParameters(event));
        }
        return countBatchWrites(executeBatch(INSERT_SQL, rows));
    }
}
