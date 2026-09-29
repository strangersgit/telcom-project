package com.amdocs.telecom.dao;

import com.amdocs.telecom.dto.CategoryIncidentDTO;
import com.amdocs.telecom.dto.DashboardStatsDTO;
import com.amdocs.telecom.dto.EngineerRecommendationDTO;
import com.amdocs.telecom.dto.EngineerWorkloadDTO;
import com.amdocs.telecom.dto.OpenTicketDTO;
import com.amdocs.telecom.dto.RepeatIncidentDTO;
import com.amdocs.telecom.dto.SlaComplianceDTO;
import com.amdocs.telecom.dto.TicketDetailDTO;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.model.enums.Specialization;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Read only access to the views and stored procedures built in the database
 * scripts.
 *
 * <p>Separate from the entity DAOs on purpose. These return joined and
 * aggregated shapes rather than rows of one table, and the work of joining
 * and aggregating stays in the database where it can use the indexes,
 * instead of being reassembled in Java from several queries.</p>
 */
public interface ReportDAO {

    /* ---------- vw_ticket_details ---------- */

    Optional<TicketDetailDTO> findTicketDetail(String ticketNumber);

    List<TicketDetailDTO> findTicketDetailsForCustomer(Long customerId);

    List<TicketDetailDTO> findTicketDetailsForEngineer(Long engineerId);

    /* ---------- vw_open_tickets ---------- */

    /**
     * The service desk queue, most urgent first.
     */
    List<OpenTicketDTO> findOpenTickets();

    /**
     * Open tickets currently at risk or already breached, which is what the
     * manager dashboard leads with.
     */
    List<OpenTicketDTO> findTicketsNeedingAttention();

    /* ---------- vw_engineer_workload ---------- */

    List<EngineerWorkloadDTO> findEngineerWorkload();

    /* ---------- vw_sla_compliance ---------- */

    List<SlaComplianceDTO> findSlaCompliance();

    /* ---------- vw_category_incidents ---------- */

    List<CategoryIncidentDTO> findCategoryIncidents();

    /* ---------- vw_customer_repeat_incidents ---------- */

    List<RepeatIncidentDTO> findRepeatIncidents();

    /* ---------- vw_manager_dashboard ---------- */

    /**
     * The single row of headline figures behind the manager dashboard.
     */
    DashboardStatsDTO findDashboardStats();

    /* ---------- Stored procedures ---------- */

    /**
     * Runs {@code sp_recommend_engineers}, the database side of the
     * assignment engine.
     */
    List<EngineerRecommendationDTO> recommendEngineers(Specialization specialization,
                                                       Region region, int limit);

    /**
     * Runs {@code sp_ticket_volume_report} for a date range.
     *
     * @return one row per day, each a list of column values in the order the
     *         procedure declares them
     */
    List<Object[]> ticketVolumeReport(LocalDate from, LocalDate to);

    /**
     * Result of running one of the stored procedures that does a whole flow
     * inside the database and reports back through OUT parameters.
     *
     * <p>{@code sp_assign_engineer}, {@code sp_escalate_ticket} and
     * {@code sp_resolve_ticket} all answer this way: a status word and a
     * sentence. None of them throws when it refuses, so a refusal arrives
     * here as a result to be read rather than as an exception to be
     * caught.</p>
     */
    final class ProcedureOutcome {

        private final String status;
        private final String message;

        public ProcedureOutcome(String status, String message) {
            this.status = status;
            this.message = message;
        }

        public String getStatus() {
            return status;
        }

        public String getMessage() {
            return message;
        }

        public boolean isSuccess() {
            return "SUCCESS".equalsIgnoreCase(status);
        }

        @Override
        public String toString() {
            return status + ": " + message;
        }
    }

    /**
     * Calls {@code sp_assign_engineer}. The Java path in section 9 does the
     * same work through the DAOs; this one exists so the stored procedure
     * required by the case study is genuinely exercised.
     */
    ProcedureOutcome assignEngineerViaProcedure(Long ticketId, Long engineerId, String actor);

    /**
     * Calls {@code sp_escalate_ticket}, which moves a ticket one rung and
     * writes the escalation, status and audit trails itself.
     *
     * @param automatic whether the SLA engine decided, which the procedure
     *                  records in {@code escalation_history.auto_escalated}
     */
    ProcedureOutcome escalateViaProcedure(Long ticketId, String reason, String actor,
                                          boolean automatic);

    /**
     * Calls {@code sp_resolve_ticket}, which records the resolution, gives
     * the engineer their capacity back and writes the status and audit
     * trails itself.
     *
     * <p>Refuses a ticket that is already RESOLVED, CLOSED or CANCELLED,
     * reporting that through the outcome rather than by throwing.</p>
     */
    ProcedureOutcome resolveViaProcedure(Long ticketId, String rootCause, String resolution,
                                         String resolutionCode, String actor);
}
