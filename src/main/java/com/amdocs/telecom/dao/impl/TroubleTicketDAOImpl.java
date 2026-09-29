package com.amdocs.telecom.dao.impl;

import com.amdocs.telecom.dao.RowMapper;
import com.amdocs.telecom.dao.TroubleTicketDAO;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.enums.EscalationLevel;
import com.amdocs.telecom.model.enums.IncidentCategory;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.model.enums.ResolutionCode;
import com.amdocs.telecom.model.enums.SLAStatus;
import com.amdocs.telecom.model.enums.Severity;
import com.amdocs.telecom.model.enums.TicketStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * JDBC access to the {@code trouble_tickets} table.
 *
 * <p>Several of the updates here carry a guard in the WHERE clause rather
 * than checking in Java first. That is deliberate: a check followed by a
 * separate write leaves a gap in which another thread can change the same
 * ticket, and the background SLA monitor means there genuinely is another
 * thread.</p>
 */
public class TroubleTicketDAOImpl extends AbstractJdbcDAO<TroubleTicket>
        implements TroubleTicketDAO {

    /** States that no longer count as work in progress. */
    private static final String FINISHED = "('RESOLVED', 'CLOSED', 'CANCELLED')";

    private static final String INSERT_SQL =
            "INSERT INTO trouble_tickets (ticket_number, customer_id, service_id, category, "
                    + "description, priority, severity, status, assigned_engineer_id, "
                    + "escalation_level, sla_status, created_date, sla_response_deadline, "
                    + "sla_deadline, auto_created, created_by) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE trouble_tickets SET category = ?, description = ?, priority = ?, severity = ?, "
                    + "status = ?, assigned_engineer_id = ?, escalation_level = ?, sla_status = ?, "
                    + "root_cause = ?, resolution = ?, resolution_code = ? WHERE ticket_id = ?";

    @Override
    public String tableName() {
        return "trouble_tickets";
    }

    @Override
    protected String idColumn() {
        return "ticket_id";
    }

    @Override
    protected String defaultOrderBy() {
        return "created_date DESC";
    }

    @Override
    protected RowMapper<TroubleTicket> mapper() {
        return TroubleTicketDAOImpl::mapRow;
    }

    static TroubleTicket mapRow(ResultSet resultSet) throws SQLException {
        TroubleTicket ticket = new TroubleTicket();
        ticket.setId(resultSet.getLong("ticket_id"));
        ticket.setTicketNumber(resultSet.getString("ticket_number"));
        ticket.setCustomerId(resultSet.getLong("customer_id"));
        ticket.setServiceId(resultSet.getLong("service_id"));
        ticket.setCategory(JdbcSupport.enumValue(resultSet, "category", IncidentCategory.class));
        ticket.setDescription(resultSet.getString("description"));
        ticket.setPriority(JdbcSupport.enumValue(resultSet, "priority", Priority.class));
        ticket.setSeverity(JdbcSupport.enumValue(resultSet, "severity", Severity.class));
        ticket.setStatus(JdbcSupport.enumValue(resultSet, "status", TicketStatus.class));
        ticket.setAssignedEngineerId(JdbcSupport.nullableLong(resultSet, "assigned_engineer_id"));
        ticket.setEscalationLevel(
                JdbcSupport.enumValue(resultSet, "escalation_level", EscalationLevel.class));
        ticket.setSlaStatus(JdbcSupport.enumValue(resultSet, "sla_status", SLAStatus.class));
        ticket.setCreatedDate(JdbcSupport.localDateTime(resultSet, "created_date"));
        ticket.setAssignedDate(JdbcSupport.localDateTime(resultSet, "assigned_date"));
        ticket.setSlaResponseDeadline(JdbcSupport.localDateTime(resultSet, "sla_response_deadline"));
        ticket.setSlaDeadline(JdbcSupport.localDateTime(resultSet, "sla_deadline"));
        ticket.setFirstResponseDate(JdbcSupport.localDateTime(resultSet, "first_response_date"));
        ticket.setResolutionDate(JdbcSupport.localDateTime(resultSet, "resolution_date"));
        ticket.setClosedDate(JdbcSupport.localDateTime(resultSet, "closed_date"));
        ticket.setRootCause(resultSet.getString("root_cause"));
        ticket.setResolution(resultSet.getString("resolution"));
        ticket.setResolutionCode(
                JdbcSupport.enumValue(resultSet, "resolution_code", ResolutionCode.class));
        ticket.setAutoCreated(resultSet.getBoolean("auto_created"));
        ticket.setCreatedBy(resultSet.getString("created_by"));
        ticket.setCreatedAt(JdbcSupport.localDateTime(resultSet, "created_at"));
        ticket.setUpdatedAt(JdbcSupport.localDateTime(resultSet, "updated_at"));
        return ticket;
    }

    @Override
    protected String insertSql() {
        return INSERT_SQL;
    }

    /**
     * The SLA deadlines are passed through as they are, including when they
     * are null.
     *
     * <p>{@code trg_tickets_before_insert} fills either column only if it
     * arrives null, so the two routes into this table cooperate rather than
     * compete. A ticket raised through the SLA engine carries deadlines
     * computed by the clock configured for its band and the trigger leaves
     * them alone; a row inserted by any other route still gets the round the
     * clock default, so no ticket is ever left without an SLA.</p>
     */
    @Override
    protected Object[] insertParameters(TroubleTicket ticket) {
        return new Object[]{
                ticket.getTicketNumber(),
                ticket.getCustomerId(),
                ticket.getServiceId(),
                ticket.getCategory(),
                ticket.getDescription(),
                ticket.getPriority(),
                ticket.getSeverity(),
                ticket.getStatus(),
                ticket.getAssignedEngineerId(),
                ticket.getEscalationLevel(),
                ticket.getSlaStatus(),
                ticket.getCreatedDate() == null ? LocalDateTime.now() : ticket.getCreatedDate(),
                ticket.getSlaResponseDeadline(),
                ticket.getSlaDeadline(),
                ticket.isAutoCreated(),
                ticket.getCreatedBy()
        };
    }

    @Override
    protected String updateSql() {
        return UPDATE_SQL;
    }

    @Override
    protected Object[] updateParameters(TroubleTicket ticket) {
        return new Object[]{
                ticket.getCategory(),
                ticket.getDescription(),
                ticket.getPriority(),
                ticket.getSeverity(),
                ticket.getStatus(),
                ticket.getAssignedEngineerId(),
                ticket.getEscalationLevel(),
                ticket.getSlaStatus(),
                ticket.getRootCause(),
                ticket.getResolution(),
                ticket.getResolutionCode(),
                ticket.getId()
        };
    }

    /* ---------- Finders ---------- */

    @Override
    public Optional<TroubleTicket> findByTicketNumber(String ticketNumber) {
        return queryOne("SELECT * FROM trouble_tickets WHERE ticket_number = ?", ticketNumber);
    }

    @Override
    public List<TroubleTicket> findByCustomerId(Long customerId) {
        return query("SELECT * FROM trouble_tickets WHERE customer_id = ? "
                + "ORDER BY created_date DESC", customerId);
    }

    @Override
    public List<TroubleTicket> findByEngineerId(Long engineerId) {
        return query("SELECT * FROM trouble_tickets WHERE assigned_engineer_id = ? "
                + "ORDER BY created_date DESC", engineerId);
    }

    @Override
    public List<TroubleTicket> findByStatus(TicketStatus status) {
        return query("SELECT * FROM trouble_tickets WHERE status = ? ORDER BY created_date DESC",
                status);
    }

    @Override
    public List<TroubleTicket> findByPriority(Priority priority) {
        return query("SELECT * FROM trouble_tickets WHERE priority = ? ORDER BY created_date DESC",
                priority);
    }

    @Override
    public List<TroubleTicket> findByCategory(IncidentCategory category) {
        return query("SELECT * FROM trouble_tickets WHERE category = ? ORDER BY created_date DESC",
                category);
    }

    /**
     * Ordered the way the service desk wants to see the queue: the CASE
     * turns the priority name into a sortable weight, and among equals the
     * oldest ticket comes first.
     */
    @Override
    public List<TroubleTicket> findOpen() {
        return query("SELECT * FROM trouble_tickets WHERE status NOT IN " + FINISHED
                + " ORDER BY CASE priority WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 "
                + "WHEN 'MEDIUM' THEN 2 ELSE 1 END DESC, created_date ASC");
    }

    @Override
    public List<TroubleTicket> findUnassigned() {
        return query("SELECT * FROM trouble_tickets WHERE assigned_engineer_id IS NULL "
                + "AND status NOT IN " + FINISHED
                + " ORDER BY CASE priority WHEN 'CRITICAL' THEN 4 WHEN 'HIGH' THEN 3 "
                + "WHEN 'MEDIUM' THEN 2 ELSE 1 END DESC, created_date ASC");
    }

    @Override
    public List<TroubleTicket> findDueBefore(LocalDateTime cutoff) {
        return query("SELECT * FROM trouble_tickets WHERE status NOT IN " + FINISHED
                + " AND sla_deadline IS NOT NULL AND sla_deadline <= ? ORDER BY sla_deadline ASC",
                cutoff);
    }

    @Override
    public List<TroubleTicket> findBreached() {
        return query("SELECT * FROM trouble_tickets WHERE status NOT IN " + FINISHED
                + " AND sla_deadline IS NOT NULL AND sla_deadline < NOW() ORDER BY sla_deadline ASC");
    }

    @Override
    public List<TroubleTicket> findCreatedBetween(LocalDateTime from, LocalDateTime to) {
        return query("SELECT * FROM trouble_tickets WHERE created_date BETWEEN ? AND ? "
                + "ORDER BY created_date ASC", from, to);
    }

    /**
     * Reads the numeric tail of the highest ticket number for the year.
     * Doing it in SQL means the sequence is taken from what is actually
     * stored rather than from a counter that could drift.
     */
    @Override
    public int findHighestSequenceForYear(int year) {
        String prefix = "TT-" + year + "-";
        return queryLong("SELECT MAX(CAST(SUBSTRING(ticket_number, ?) AS UNSIGNED)) "
                        + "FROM trouble_tickets WHERE ticket_number LIKE ?",
                prefix.length() + 1, prefix + "%")
                .orElse(0L)
                .intValue();
    }

    /* ---------- State changes ---------- */

    @Override
    public boolean updateStatus(Long ticketId, TicketStatus expectedStatus,
                                TicketStatus newStatus) {
        return executeUpdate("UPDATE trouble_tickets SET status = ? "
                        + "WHERE ticket_id = ? AND status = ?",
                newStatus, ticketId, expectedStatus) > 0;
    }

    @Override
    public boolean updateRootCause(Long ticketId, String rootCause) {
        return executeUpdate("UPDATE trouble_tickets SET root_cause = ? "
                        + "WHERE ticket_id = ? AND status NOT IN " + FINISHED,
                rootCause, ticketId) > 0;
    }

    /**
     * The resolution columns are cleared in the same statement that moves
     * the status, so there is no instant at which the row says both
     * "in progress" and "resolved at". Clearing the code as well is required
     * rather than tidy: {@code chk_tickets_resolved_complete} pairs the code
     * with the date, and leaving a code behind on a ticket being worked
     * again would tell the resolution code report a fault was fixed twice.
     */
    @Override
    public boolean reopen(Long ticketId, TicketStatus expectedStatus, TicketStatus newStatus) {
        return executeUpdate("UPDATE trouble_tickets "
                        + "SET status = ?, resolution_date = NULL, resolution_code = NULL, "
                        + "    resolution = NULL, closed_date = NULL "
                        + "WHERE ticket_id = ? AND status = ?",
                newStatus, ticketId, expectedStatus) > 0;
    }

    /**
     * Assigns only if the ticket is still unassigned and still in the state
     * the caller expects, so two service desk operators acting at the same
     * moment cannot both claim it.
     */
    @Override
    public boolean assignEngineer(Long ticketId, Long engineerId, TicketStatus expectedStatus) {
        return executeUpdate("UPDATE trouble_tickets "
                        + "SET assigned_engineer_id = ?, status = ? "
                        + "WHERE ticket_id = ? AND status = ? AND assigned_engineer_id IS NULL",
                engineerId, TicketStatus.ASSIGNED, ticketId, expectedStatus) > 0;
    }

    /**
     * The status is absent from the SET clause on purpose: see the interface
     * comment. Finished tickets are excluded because who was working on a
     * closed ticket is part of the record.
     */
    @Override
    public boolean reassignEngineer(Long ticketId, Long newEngineerId, Long expectedEngineerId) {
        return executeUpdate("UPDATE trouble_tickets "
                        + "SET assigned_engineer_id = ? "
                        + "WHERE ticket_id = ? AND assigned_engineer_id = ? "
                        + "  AND status NOT IN " + FINISHED,
                newEngineerId, ticketId, expectedEngineerId) > 0;
    }

    /**
     * Guarded against finished tickets. The priority a closed ticket was
     * handled at is part of what happened, and the SLA reports read it back
     * to work out which window applied.
     */
    @Override
    public boolean updatePriority(Long ticketId, Priority priority) {
        return executeUpdate("UPDATE trouble_tickets SET priority = ? "
                        + "WHERE ticket_id = ? AND status NOT IN " + FINISHED,
                priority, ticketId) > 0;
    }

    @Override
    public boolean updateDeadlines(Long ticketId, LocalDateTime responseDeadline,
                                   LocalDateTime resolutionDeadline) {
        return executeUpdate("UPDATE trouble_tickets "
                        + "SET sla_response_deadline = ?, sla_deadline = ? "
                        + "WHERE ticket_id = ? AND status NOT IN " + FINISHED,
                responseDeadline, resolutionDeadline, ticketId) > 0;
    }

    /**
     * The {@code sla_status <> ?} guard turns a write of the value already
     * stored into no rows changed, so the SLA monitor's count is of tickets
     * that genuinely moved. It also means two monitor passes racing each
     * other cannot both claim the same correction.
     */
    @Override
    public boolean updateSlaStatus(Long ticketId, SLAStatus slaStatus) {
        return executeUpdate("UPDATE trouble_tickets SET sla_status = ? "
                        + "WHERE ticket_id = ? AND sla_status <> ?",
                slaStatus, ticketId, slaStatus) > 0;
    }

    @Override
    public boolean updateEscalation(Long ticketId, EscalationLevel level) {
        return executeUpdate("UPDATE trouble_tickets SET escalation_level = ?, status = ? "
                        + "WHERE ticket_id = ? AND status NOT IN " + FINISHED,
                level, TicketStatus.ESCALATED, ticketId) > 0;
    }

    @Override
    public boolean escalate(Long ticketId, EscalationLevel expectedLevel,
                            EscalationLevel newLevel, TicketStatus expectedStatus) {
        return executeUpdate("UPDATE trouble_tickets SET escalation_level = ?, status = ? "
                        + "WHERE ticket_id = ? AND escalation_level = ? AND status = ? "
                        + "  AND status NOT IN " + FINISHED,
                newLevel, TicketStatus.ESCALATED, ticketId, expectedLevel, expectedStatus) > 0;
    }

    /**
     * Stamps the first response only once; a later call finds the column
     * already set and changes nothing.
     */
    @Override
    public boolean recordFirstResponse(Long ticketId, LocalDateTime respondedAt) {
        return executeUpdate("UPDATE trouble_tickets SET first_response_date = ? "
                        + "WHERE ticket_id = ? AND first_response_date IS NULL",
                respondedAt, ticketId) > 0;
    }

    /**
     * The resolution code is written in the same statement as the status
     * because {@code chk_tickets_resolved_complete} refuses a resolved
     * ticket that has one without the other.
     */
    @Override
    public boolean resolve(Long ticketId, TicketStatus expectedStatus,
                           ResolutionCode resolutionCode, String rootCause, String resolution) {
        return executeUpdate("UPDATE trouble_tickets "
                        + "SET status = ?, resolution_code = ?, root_cause = ?, resolution = ?, "
                        + "    resolution_date = NOW() "
                        + "WHERE ticket_id = ? AND status = ?",
                TicketStatus.RESOLVED, resolutionCode, rootCause, resolution, ticketId,
                expectedStatus) > 0;
    }

    @Override
    public boolean close(Long ticketId) {
        return executeUpdate("UPDATE trouble_tickets SET status = ? "
                        + "WHERE ticket_id = ? AND status = ?",
                TicketStatus.CLOSED, ticketId, TicketStatus.RESOLVED) > 0;
    }
}
