package com.amdocs.telecom.service;

import com.amdocs.telecom.dao.ReportDAO;
import com.amdocs.telecom.dto.EngineerRecommendationDTO;
import com.amdocs.telecom.model.NetworkEngineer;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.assignment.AssignmentResult;
import com.amdocs.telecom.service.assignment.EngineerMatch;

import java.util.List;
import java.util.Optional;

/**
 * Section 7's recommendation engine and section 19's assignment
 * transaction.
 *
 * <p>The reading half suggests who should take a ticket. The writing half
 * hands it over, as the single atomic unit section 19 sets out: validate the
 * ticket, validate the engineer, check availability, assign, update the
 * ticket, record the history, notify, audit, commit, and roll the whole lot
 * back on any failure.</p>
 */
public interface EngineerAssignmentService {

    /* ---------- Recommending ---------- */

    /**
     * Candidates for a ticket, closest fit first.
     *
     * <p>Ranked in Java from the roster, which is what section 7's
     * "Stream + Lambda + Comparator + Optional" asks for. Candidates found
     * only by relaxing the skill requirement are included and marked as
     * such, so the operator can see the trade-off rather than being told
     * nobody is free.</p>
     *
     * @param limit how many to return; zero or less gives the shortlist of
     *              three from section 16's worked example
     */
    List<EngineerMatch> recommend(UserSession actor, String ticketNumber, int limit);

    /**
     * The one engineer the engine would choose, if any.
     */
    Optional<EngineerMatch> recommendBest(UserSession actor, String ticketNumber);

    /**
     * The same question put to {@code sp_recommend_engineers} instead.
     *
     * <p>Exists so the stored procedure the database scripts build is
     * genuinely used, and so the two routes can be compared. It only ever
     * returns exact matches, because the procedure's WHERE clause has no
     * notion of widening the search.</p>
     */
    List<EngineerRecommendationDTO> recommendViaProcedure(UserSession actor, String ticketNumber,
                                                          int limit);

    /**
     * Section 16's worked example: the engineers with the lowest active
     * workload who hold a given skill and are currently free.
     */
    List<NetworkEngineer> leastBusySpecialists(UserSession actor, String ticketNumber, int count);

    /**
     * The service desk's work list from section 14: open tickets with
     * nobody on them, most urgent first.
     */
    List<TroubleTicket> unassignedQueue(UserSession actor);

    /* ---------- Assigning ---------- */

    /**
     * Gives a ticket to a named engineer.
     *
     * @param employeeCode the engineer's staff code, as shown on the roster
     * @throws com.amdocs.telecom.exception.BusinessException if the ticket
     *         or the engineer cannot take part
     */
    AssignmentResult assign(UserSession actor, String ticketNumber, String employeeCode);

    /**
     * Gives a ticket to whoever the engine recommends.
     *
     * @throws com.amdocs.telecom.exception.BusinessException when no
     *         engineer can be assigned automatically
     */
    AssignmentResult autoAssign(UserSession actor, String ticketNumber);

    /**
     * Moves a ticket from the engineer holding it to another, per section
     * 14's "Reassign Ticket". The ticket's status is left alone.
     *
     * @param reason why it is being moved; recorded in the trail
     */
    AssignmentResult reassign(UserSession actor, String ticketNumber, String employeeCode,
                              String reason);

    /**
     * Works through the unassigned queue, assigning what it can.
     *
     * <p>One ticket that nobody can take does not stop the rest: each
     * attempt sits on its own savepoint and only that ticket's work is
     * undone when it fails. The result has a line per ticket considered.</p>
     *
     * @param limit how many tickets to consider at most
     */
    List<AssignmentResult> sweepQueue(UserSession actor, int limit);

    /**
     * Runs the whole section 19 sequence inside the database instead,
     * through {@code sp_assign_engineer}.
     *
     * <p>The procedure reports a refusal through its OUT parameters rather
     * than by throwing, so a business refusal arrives as a result here.</p>
     */
    ReportDAO.ProcedureOutcome assignViaProcedure(UserSession actor, String ticketNumber,
                                                   String employeeCode);
}
