package com.amdocs.telecom.service;

import com.amdocs.telecom.dao.ReportDAO;
import com.amdocs.telecom.model.EscalationHistory;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.escalation.EscalationCandidate;
import com.amdocs.telecom.service.escalation.EscalationOutcome;

import java.util.List;
import java.util.Optional;

/**
 * The escalation ladder from section 9 of the case study.
 *
 * <p>Section 9 draws four rungs, engineer to team lead to network manager to
 * operations manager, and gives one condition for climbing them: the ticket
 * is approaching or exceeding its SLA. It adds one ordering rule, that
 * critical tickets are dealt with before lower priority ones, and names the
 * structure that enforces it.</p>
 *
 * <p>Two ways up, then. A person escalates one ticket because they have
 * decided it needs more weight behind it, and gets an exception when that
 * cannot be done. The SLA monitor escalates whatever has fallen behind, in
 * urgency order, and gets a line per ticket saying what became of it. Both
 * routes move one rung, write the same trail, and are refused by the same
 * rules.</p>
 */
public interface EscalationService {

    /* ---------- Looking before leaping ---------- */

    /**
     * What escalation would do to this ticket, without doing it.
     *
     * <p>Empty when the ticket is where its SLA standing says it should be,
     * which includes every ticket that is comfortably inside its window and
     * every ticket already at the top of the ladder.</p>
     */
    Optional<EscalationCandidate> assess(UserSession actor, String ticketNumber);

    /**
     * Why this ticket is, or is not, a candidate, in a sentence for the
     * console.
     */
    String explain(UserSession actor, String ticketNumber);

    /**
     * Every ticket that has fallen behind the rung it warrants, most urgent
     * first.
     *
     * <p>This is the queue section 9 asks for, drained into a list so the
     * caller can show it without consuming it. A critical ticket appears
     * before a high one regardless of which was read from the table first,
     * and among equals the one raised longest ago comes first.</p>
     *
     * @param limit how many to return, or zero for the default
     */
    List<EscalationCandidate> candidates(UserSession actor, int limit);

    /* ---------- Climbing ---------- */

    /**
     * Moves one ticket one rung because a person said so.
     *
     * <p>A person may escalate a ticket the SLA has no complaint about:
     * section 14 offers "Escalate Ticket" as a service desk action, and an
     * angry customer is a reason the SLA cannot see. What a person may not
     * do is escalate a ticket nobody is working on yet, or one that has
     * finished, or one already at the top.</p>
     *
     * @param reason required, and recorded in the escalation trail
     * @throws com.amdocs.telecom.exception.AuthorizationException when the
     *         session may not escalate
     * @throws com.amdocs.telecom.exception.BusinessException when the ticket
     *         cannot be escalated
     */
    EscalationOutcome escalate(UserSession actor, String ticketNumber, String reason);

    /**
     * Moves every ticket that has fallen behind one rung, most urgent first.
     *
     * <p>One pass moves each ticket one rung, so a ticket three rungs behind
     * catches up over three passes and leaves three lines in the trail
     * rather than one line that skips two levels. Running the sweep again
     * once everything has caught up changes nothing.</p>
     *
     * @param limit how many tickets to consider, or zero for the default
     */
    List<EscalationOutcome> sweep(UserSession actor, int limit);

    /* ---------- The trail ---------- */

    /**
     * Every step this ticket has taken up the ladder, oldest first.
     */
    List<EscalationHistory> history(UserSession actor, String ticketNumber);

    /**
     * Escalations the SLA monitor raised on its own, which the escalation
     * report breaks out from the ones people asked for.
     */
    List<EscalationHistory> automatic(UserSession actor);

    /**
     * Escalates through {@code sp_escalate_ticket} instead of through the
     * DAOs.
     *
     * <p>The Java route above does the same work. This exists so the stored
     * procedure the case study asks for is genuinely exercised, and so the
     * two can be compared.</p>
     *
     * <p>The procedure commits for itself, so this cannot be wrapped in a
     * transaction the caller means to roll back.</p>
     */
    ReportDAO.ProcedureOutcome escalateViaProcedure(UserSession actor, String ticketNumber,
                                                    String reason, boolean automatic);
}
