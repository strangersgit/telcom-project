package com.amdocs.telecom.dao.impl;

import com.amdocs.telecom.dao.ConnectionScope;
import com.amdocs.telecom.dao.ReportDAO;
import com.amdocs.telecom.dto.CategoryIncidentDTO;
import com.amdocs.telecom.dto.DashboardStatsDTO;
import com.amdocs.telecom.dto.EngineerRecommendationDTO;
import com.amdocs.telecom.dto.EngineerWorkloadDTO;
import com.amdocs.telecom.dto.OpenTicketDTO;
import com.amdocs.telecom.dto.RepeatIncidentDTO;
import com.amdocs.telecom.dto.SlaComplianceDTO;
import com.amdocs.telecom.dto.TicketDetailDTO;
import com.amdocs.telecom.exception.ErrorCode;
import com.amdocs.telecom.model.enums.CustomerType;
import com.amdocs.telecom.model.enums.EngineerAvailability;
import com.amdocs.telecom.model.enums.EscalationLevel;
import com.amdocs.telecom.model.enums.IncidentCategory;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.model.enums.ResolutionCode;
import com.amdocs.telecom.model.enums.SLAStatus;
import com.amdocs.telecom.model.enums.ServiceType;
import com.amdocs.telecom.model.enums.Severity;
import com.amdocs.telecom.model.enums.Specialization;
import com.amdocs.telecom.model.enums.TicketStatus;

import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the views and calls the stored procedures created in the database
 * scripts.
 *
 * <p>Nothing here assembles a joined shape in Java. The joins and the
 * aggregates live in the views, where the query planner can use the indexes,
 * and this class only maps the columns that come back.</p>
 */
public class ReportDAOImpl extends JdbcOperations implements ReportDAO {

    @Override
    protected String sourceName() {
        return "reporting views";
    }

    /* ---------- vw_ticket_details ---------- */

    private static TicketDetailDTO mapTicketDetail(ResultSet row) throws SQLException {
        TicketDetailDTO detail = new TicketDetailDTO();
        detail.setTicketId(row.getLong("ticket_id"));
        detail.setTicketNumber(row.getString("ticket_number"));
        detail.setCustomerNumber(row.getString("customer_number"));
        detail.setCustomerName(row.getString("customer_name"));
        detail.setCustomerType(JdbcSupport.enumValue(row, "customer_type", CustomerType.class));
        detail.setCity(row.getString("city"));
        detail.setCustomerRegion(row.getString("customer_region"));
        detail.setServiceCode(row.getString("service_code"));
        detail.setServiceName(row.getString("service_name"));
        detail.setServiceType(JdbcSupport.enumValue(row, "service_type", ServiceType.class));
        detail.setCategory(JdbcSupport.enumValue(row, "category", IncidentCategory.class));
        detail.setDescription(row.getString("description"));
        detail.setPriority(JdbcSupport.enumValue(row, "priority", Priority.class));
        detail.setSeverity(JdbcSupport.enumValue(row, "severity", Severity.class));
        detail.setStatus(JdbcSupport.enumValue(row, "status", TicketStatus.class));
        detail.setEscalationLevel(
                JdbcSupport.enumValue(row, "escalation_level", EscalationLevel.class));
        detail.setEngineerCode(row.getString("engineer_code"));
        detail.setEngineerName(row.getString("engineer_name"));
        detail.setEngineerSpecialization(
                JdbcSupport.enumValue(row, "engineer_specialization", Specialization.class));
        detail.setEngineerRegion(row.getString("engineer_region"));
        detail.setCreatedDate(JdbcSupport.localDateTime(row, "created_date"));
        detail.setAssignedDate(JdbcSupport.localDateTime(row, "assigned_date"));
        detail.setSlaResponseDeadline(JdbcSupport.localDateTime(row, "sla_response_deadline"));
        detail.setSlaDeadline(JdbcSupport.localDateTime(row, "sla_deadline"));
        detail.setResolutionDate(JdbcSupport.localDateTime(row, "resolution_date"));
        detail.setClosedDate(JdbcSupport.localDateTime(row, "closed_date"));
        detail.setRootCause(row.getString("root_cause"));
        detail.setResolution(row.getString("resolution"));
        detail.setResolutionCode(
                JdbcSupport.enumValue(row, "resolution_code", ResolutionCode.class));
        detail.setAutoCreated(row.getBoolean("auto_created"));
        detail.setLiveSlaStatus(JdbcSupport.enumValue(row, "live_sla_status", SLAStatus.class));
        detail.setMinutesRemaining(JdbcSupport.nullableLong(row, "minutes_remaining"));
        detail.setResolutionHours(JdbcSupport.nullableDouble(row, "resolution_hours"));
        return detail;
    }

    @Override
    public Optional<TicketDetailDTO> findTicketDetail(String ticketNumber) {
        return queryOne("SELECT * FROM vw_ticket_details WHERE ticket_number = ?",
                ReportDAOImpl::mapTicketDetail, ticketNumber);
    }

    @Override
    public List<TicketDetailDTO> findTicketDetailsForCustomer(Long customerId) {
        return query("SELECT d.* FROM vw_ticket_details d "
                        + "INNER JOIN trouble_tickets t ON t.ticket_id = d.ticket_id "
                        + "WHERE t.customer_id = ? ORDER BY d.created_date DESC",
                ReportDAOImpl::mapTicketDetail, customerId);
    }

    @Override
    public List<TicketDetailDTO> findTicketDetailsForEngineer(Long engineerId) {
        return query("SELECT d.* FROM vw_ticket_details d "
                        + "INNER JOIN trouble_tickets t ON t.ticket_id = d.ticket_id "
                        + "WHERE t.assigned_engineer_id = ? ORDER BY d.created_date DESC",
                ReportDAOImpl::mapTicketDetail, engineerId);
    }

    /* ---------- vw_open_tickets ---------- */

    private static OpenTicketDTO mapOpenTicket(ResultSet row) throws SQLException {
        OpenTicketDTO ticket = new OpenTicketDTO();
        ticket.setTicketId(row.getLong("ticket_id"));
        ticket.setTicketNumber(row.getString("ticket_number"));
        ticket.setCustomerNumber(row.getString("customer_number"));
        ticket.setCustomerName(row.getString("customer_name"));
        ticket.setServiceName(row.getString("service_name"));
        ticket.setCategory(JdbcSupport.enumValue(row, "category", IncidentCategory.class));
        ticket.setPriority(JdbcSupport.enumValue(row, "priority", Priority.class));
        ticket.setSeverity(JdbcSupport.enumValue(row, "severity", Severity.class));
        ticket.setStatus(JdbcSupport.enumValue(row, "status", TicketStatus.class));
        ticket.setEscalationLevel(
                JdbcSupport.enumValue(row, "escalation_level", EscalationLevel.class));
        ticket.setEngineerCode(row.getString("engineer_code"));
        ticket.setCreatedDate(JdbcSupport.localDateTime(row, "created_date"));
        ticket.setSlaDeadline(JdbcSupport.localDateTime(row, "sla_deadline"));
        ticket.setSlaStatus(JdbcSupport.enumValue(row, "sla_status", SLAStatus.class));
        ticket.setMinutesRemaining(JdbcSupport.nullableLong(row, "minutes_remaining"));
        ticket.setPriorityWeight(row.getInt("priority_weight"));
        return ticket;
    }

    @Override
    public List<OpenTicketDTO> findOpenTickets() {
        return query("SELECT * FROM vw_open_tickets "
                        + "ORDER BY priority_weight DESC, created_date ASC",
                ReportDAOImpl::mapOpenTicket);
    }

    @Override
    public List<OpenTicketDTO> findTicketsNeedingAttention() {
        return query("SELECT * FROM vw_open_tickets WHERE sla_status IN ('AT_RISK', 'BREACHED') "
                        + "ORDER BY minutes_remaining ASC",
                ReportDAOImpl::mapOpenTicket);
    }

    /* ---------- vw_engineer_workload ---------- */

    private static EngineerWorkloadDTO mapWorkload(ResultSet row) throws SQLException {
        EngineerWorkloadDTO workload = new EngineerWorkloadDTO();
        workload.setEngineerId(row.getLong("engineer_id"));
        workload.setEmployeeCode(row.getString("employee_code"));
        workload.setEngineerName(row.getString("engineer_name"));
        workload.setSpecialization(
                JdbcSupport.enumValue(row, "specialization", Specialization.class));
        workload.setRegion(JdbcSupport.enumValue(row, "region", Region.class));
        workload.setExperienceYears(row.getInt("experience_years"));
        workload.setAvailability(
                JdbcSupport.enumValue(row, "availability", EngineerAvailability.class));
        workload.setActiveTicketCount(row.getInt("active_ticket_count"));
        workload.setMaxTicketCapacity(row.getInt("max_ticket_capacity"));
        workload.setTotalAssigned(row.getInt("total_assigned"));
        workload.setTotalResolved(row.getInt("total_resolved"));
        workload.setCurrentlyOpen(row.getInt("currently_open"));
        workload.setSlaBreaches(row.getInt("sla_breaches"));
        workload.setAvgResolutionHours(JdbcSupport.nullableDouble(row, "avg_resolution_hours"));
        return workload;
    }

    @Override
    public List<EngineerWorkloadDTO> findEngineerWorkload() {
        return query("SELECT * FROM vw_engineer_workload ORDER BY employee_code",
                ReportDAOImpl::mapWorkload);
    }

    /* ---------- vw_sla_compliance ---------- */

    private static SlaComplianceDTO mapCompliance(ResultSet row) throws SQLException {
        SlaComplianceDTO compliance = new SlaComplianceDTO();
        compliance.setPriority(JdbcSupport.enumValue(row, "priority", Priority.class));
        compliance.setResponseMinutes(row.getInt("response_minutes"));
        compliance.setResolutionMinutes(row.getInt("resolution_minutes"));
        compliance.setTotalTickets(row.getInt("total_tickets"));
        compliance.setCompletedTickets(row.getInt("completed_tickets"));
        compliance.setMetSla(row.getInt("met_sla"));
        compliance.setBreachedSla(row.getInt("breached_sla"));
        compliance.setCompliancePercent(JdbcSupport.nullableDouble(row, "compliance_pct"));
        compliance.setAvgResolutionHours(JdbcSupport.nullableDouble(row, "avg_resolution_hours"));
        return compliance;
    }

    /**
     * Ordered by the resolution window so the tightest band is reported
     * first, which is the order the case study tabulates them in.
     */
    @Override
    public List<SlaComplianceDTO> findSlaCompliance() {
        return query("SELECT * FROM vw_sla_compliance ORDER BY resolution_minutes ASC",
                ReportDAOImpl::mapCompliance);
    }

    /* ---------- vw_category_incidents ---------- */

    private static CategoryIncidentDTO mapCategory(ResultSet row) throws SQLException {
        CategoryIncidentDTO incident = new CategoryIncidentDTO();
        incident.setCategory(JdbcSupport.enumValue(row, "category", IncidentCategory.class));
        incident.setTicketCount(row.getInt("ticket_count"));
        incident.setCriticalCount(row.getInt("critical_count"));
        incident.setAutoCreatedCount(row.getInt("auto_created_count"));
        incident.setAvgResolutionHours(JdbcSupport.nullableDouble(row, "avg_resolution_hours"));
        incident.setPctOfTotal(JdbcSupport.nullableDouble(row, "pct_of_total"));
        return incident;
    }

    @Override
    public List<CategoryIncidentDTO> findCategoryIncidents() {
        return query("SELECT * FROM vw_category_incidents ORDER BY ticket_count DESC",
                ReportDAOImpl::mapCategory);
    }

    /* ---------- vw_customer_repeat_incidents ---------- */

    private static RepeatIncidentDTO mapRepeat(ResultSet row) throws SQLException {
        RepeatIncidentDTO repeat = new RepeatIncidentDTO();
        repeat.setCustomerId(row.getLong("customer_id"));
        repeat.setCustomerNumber(row.getString("customer_number"));
        repeat.setCustomerName(row.getString("customer_name"));
        repeat.setCustomerType(JdbcSupport.enumValue(row, "customer_type", CustomerType.class));
        repeat.setCity(row.getString("city"));
        repeat.setRegion(JdbcSupport.enumValue(row, "region", Region.class));
        repeat.setIncidentCount(row.getInt("incident_count"));
        repeat.setCriticalIncidents(row.getInt("critical_incidents"));
        repeat.setLatestIncident(JdbcSupport.localDateTime(row, "latest_incident"));
        repeat.setDaysSinceLast(JdbcSupport.nullableInteger(row, "days_since_last"));
        return repeat;
    }

    @Override
    public List<RepeatIncidentDTO> findRepeatIncidents() {
        return query("SELECT * FROM vw_customer_repeat_incidents "
                        + "ORDER BY incident_count DESC, critical_incidents DESC",
                ReportDAOImpl::mapRepeat);
    }

    /* ---------- vw_manager_dashboard ---------- */

    private static DashboardStatsDTO mapDashboard(ResultSet row) throws SQLException {
        DashboardStatsDTO stats = new DashboardStatsDTO();
        stats.setTotalOpenTickets(row.getInt("total_open_tickets"));
        stats.setCriticalIncidents(row.getInt("critical_incidents"));
        stats.setSlaAtRisk(row.getInt("sla_at_risk"));
        stats.setSlaBreached(row.getInt("sla_breached"));
        stats.setResolvedToday(row.getInt("resolved_today"));
        stats.setAvgResolutionHours(JdbcSupport.nullableDouble(row, "avg_resolution_hours"));
        stats.setTotalTickets(row.getInt("total_tickets"));
        return stats;
    }

    /**
     * The view always yields exactly one row, but an empty ticket table
     * would make every figure null, so an all zero result is returned rather
     * than nothing at all.
     */
    @Override
    public DashboardStatsDTO findDashboardStats() {
        return queryOne("SELECT * FROM vw_manager_dashboard", ReportDAOImpl::mapDashboard)
                .orElseGet(DashboardStatsDTO::new);
    }

    /* ---------- Stored procedures ---------- */

    private static EngineerRecommendationDTO mapRecommendation(ResultSet row) throws SQLException {
        EngineerRecommendationDTO recommendation = new EngineerRecommendationDTO();
        recommendation.setEngineerId(row.getLong("engineer_id"));
        recommendation.setEmployeeCode(row.getString("employee_code"));
        recommendation.setEngineerName(row.getString("engineer_name"));
        recommendation.setSpecialization(
                JdbcSupport.enumValue(row, "specialization", Specialization.class));
        recommendation.setRegion(JdbcSupport.enumValue(row, "region", Region.class));
        recommendation.setExperienceYears(row.getInt("experience_years"));
        recommendation.setActiveTicketCount(row.getInt("active_ticket_count"));
        recommendation.setMaxTicketCapacity(row.getInt("max_ticket_capacity"));
        recommendation.setSpareCapacity(row.getInt("spare_capacity"));
        return recommendation;
    }

    @Override
    public List<EngineerRecommendationDTO> recommendEngineers(Specialization specialization,
                                                              Region region, int limit) {
        final String call = "{CALL sp_recommend_engineers(?, ?, ?)}";
        try (ConnectionScope scope = ConnectionScope.open();
             CallableStatement statement = scope.connection().prepareCall(call)) {
            statement.setString(1, specialization == null ? null : specialization.name());
            if (region == null) {
                statement.setNull(2, Types.VARCHAR);
            } else {
                statement.setString(2, region.name());
            }
            statement.setInt(3, limit);

            try (ResultSet resultSet = statement.executeQuery()) {
                List<EngineerRecommendationDTO> recommendations = new ArrayList<>();
                while (resultSet.next()) {
                    recommendations.add(mapRecommendation(resultSet));
                }
                return recommendations;
            }
        } catch (SQLException cause) {
            throw failure(ErrorCode.DB_QUERY_FAILED, "Procedure call failed", call, null, cause);
        }
    }

    /**
     * Returns raw column values because the volume report is tabular by
     * nature: one row per day, and the console prints it as a grid rather
     * than mapping it onto a type.
     */
    @Override
    public List<Object[]> ticketVolumeReport(LocalDate from, LocalDate to) {
        final String call = "{CALL sp_ticket_volume_report(?, ?)}";
        try (ConnectionScope scope = ConnectionScope.open();
             CallableStatement statement = scope.connection().prepareCall(call)) {
            statement.setDate(1, java.sql.Date.valueOf(from));
            statement.setDate(2, java.sql.Date.valueOf(to));

            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                int columns = metaData.getColumnCount();
                List<Object[]> rows = new ArrayList<>();
                while (resultSet.next()) {
                    Object[] values = new Object[columns];
                    for (int index = 1; index <= columns; index++) {
                        values[index - 1] = resultSet.getObject(index);
                    }
                    rows.add(values);
                }
                return rows;
            }
        } catch (SQLException cause) {
            throw failure(ErrorCode.DB_QUERY_FAILED, "Procedure call failed", call, null, cause);
        }
    }

    /**
     * The procedure manages its own transaction and reports the outcome
     * through OUT parameters rather than by throwing, so a refused
     * assignment reads as a result here rather than as an exception.
     */
    @Override
    public ProcedureOutcome assignEngineerViaProcedure(Long ticketId, Long engineerId, String actor) {
        final String call = "{CALL sp_assign_engineer(?, ?, ?, ?, ?)}";
        try (ConnectionScope scope = ConnectionScope.open();
             CallableStatement statement = scope.connection().prepareCall(call)) {
            statement.setLong(1, ticketId);
            statement.setLong(2, engineerId);
            statement.setString(3, actor);
            statement.registerOutParameter(4, Types.VARCHAR);
            statement.registerOutParameter(5, Types.VARCHAR);

            statement.execute();
            return new ProcedureOutcome(statement.getString(4), statement.getString(5));
        } catch (SQLException cause) {
            throw failure(ErrorCode.DB_TRANSACTION_FAILED, "Procedure call failed", call, null, cause);
        }
    }

    @Override
    public ProcedureOutcome escalateViaProcedure(Long ticketId, String reason, String actor,
                                                 boolean automatic) {
        final String call = "{CALL sp_escalate_ticket(?, ?, ?, ?, ?, ?)}";
        try (ConnectionScope scope = ConnectionScope.open();
             CallableStatement statement = scope.connection().prepareCall(call)) {
            statement.setLong(1, ticketId);
            statement.setString(2, reason);
            statement.setString(3, actor);
            statement.setBoolean(4, automatic);
            statement.registerOutParameter(5, Types.VARCHAR);
            statement.registerOutParameter(6, Types.VARCHAR);

            statement.execute();
            return new ProcedureOutcome(statement.getString(5), statement.getString(6));
        } catch (SQLException cause) {
            throw failure(ErrorCode.DB_TRANSACTION_FAILED, "Procedure call failed", call, null, cause);
        }
    }

    @Override
    public ProcedureOutcome resolveViaProcedure(Long ticketId, String rootCause, String resolution,
                                                String resolutionCode, String actor) {
        final String call = "{CALL sp_resolve_ticket(?, ?, ?, ?, ?, ?, ?)}";
        try (ConnectionScope scope = ConnectionScope.open();
             CallableStatement statement = scope.connection().prepareCall(call)) {
            statement.setLong(1, ticketId);
            statement.setString(2, rootCause);
            statement.setString(3, resolution);
            statement.setString(4, resolutionCode);
            statement.setString(5, actor);
            statement.registerOutParameter(6, Types.VARCHAR);
            statement.registerOutParameter(7, Types.VARCHAR);

            statement.execute();
            return new ProcedureOutcome(statement.getString(6), statement.getString(7));
        } catch (SQLException cause) {
            throw failure(ErrorCode.DB_TRANSACTION_FAILED, "Procedure call failed", call, null, cause);
        }
    }
}
