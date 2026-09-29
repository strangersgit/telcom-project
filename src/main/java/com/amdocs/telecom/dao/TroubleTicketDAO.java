package com.amdocs.telecom.dao;

import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.enums.EscalationLevel;
import com.amdocs.telecom.model.enums.IncidentCategory;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.model.enums.ResolutionCode;
import com.amdocs.telecom.model.enums.SLAStatus;
import com.amdocs.telecom.model.enums.TicketStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The central table. Finders here are deliberately narrow; anything needing
 * customer or engineer detail alongside the ticket goes through
 * {@link ReportDAO} and its joined projections instead.
 */
public interface TroubleTicketDAO extends GenericDAO<TroubleTicket, Long> {

    Optional<TroubleTicket> findByTicketNumber(String ticketNumber);

    List<TroubleTicket> findByCustomerId(Long customerId);

    List<TroubleTicket> findByEngineerId(Long engineerId);

    List<TroubleTicket> findByStatus(TicketStatus status);

    List<TroubleTicket> findByPriority(Priority priority);

    List<TroubleTicket> findByCategory(IncidentCategory category);

    /**
     * Everything not yet resolved, closed or cancelled.
     */
    List<TroubleTicket> findOpen();

    /**
     * Open tickets with no engineer, which is the service desk's work list.
     */
    List<TroubleTicket> findUnassigned();

    /**
     * Open tickets whose deadline falls inside the given window, used by the
     * SLA monitor to decide who to warn.
     */
    List<TroubleTicket> findDueBefore(LocalDateTime cutoff);

    /**
     * Open tickets already past their deadline.
     */
    List<TroubleTicket> findBreached();

    List<TroubleTicket> findCreatedBetween(LocalDateTime from, LocalDateTime to);

    /**
     * Highest sequence number issued for the year, so ticket numbering
     * continues from where it left off.
     */
    int findHighestSequenceForYear(int year);

    /**
     * Moves the ticket to a new status, but only from the status the caller
     * believes it is in. The database trigger stamps the matching date
     * column, so callers do not have to remember which one.
     *
     * <p>The guard matters because a caller reads the ticket, decides the
     * move is legal, and only then writes. Between those two moments
     * somebody else may have moved it. Naming the expected status turns that
     * race into false rather than into a ticket that skipped a state.</p>
     *
     * @return false when the ticket has moved on, or does not exist
     */
    boolean updateStatus(Long ticketId, TicketStatus expectedStatus, TicketStatus newStatus);

    /**
     * Records a diagnosis without resolving the ticket, which is section
     * 10's separate root cause update.
     */
    boolean updateRootCause(Long ticketId, String rootCause);

    /**
     * Brings a resolved ticket back into progress and clears the resolution
     * that did not hold.
     *
     * <p>The clearing is the point. A reopened ticket that kept its
     * {@code resolution_date} would look finished to anything measuring how
     * long it took, and the SLA engine stops its clock at that date, so the
     * ticket would sit in progress accruing no elapsed time at all.</p>
     */
    boolean reopen(Long ticketId, TicketStatus expectedStatus, TicketStatus newStatus);

    /**
     * Attaches an engineer and moves the ticket to ASSIGNED in one statement.
     *
     * <p>The {@code status} guard means an assignment can only land on a
     * ticket still in the state the caller checked. If something else moved
     * it first, no row changes and the caller sees false rather than quietly
     * overwriting the other change.</p>
     */
    boolean assignEngineer(Long ticketId, Long engineerId, TicketStatus expectedStatus);

    /**
     * Moves a ticket from one engineer to another, leaving its status alone.
     *
     * <p>Reassignment changes who owns the ticket, not what state it is in.
     * A ticket being actively worked is still being actively worked after it
     * changes hands, and the transition graph governs status moves rather
     * than ownership, so there is no In Progress to Assigned edge for this
     * to use.</p>
     *
     * <p>The guard is the engineer the caller believes holds it. If somebody
     * else reassigned it first, no row changes and the caller is told,
     * instead of the second reassignment silently winning.</p>
     *
     * @param expectedEngineerId the engineer the ticket is expected to be
     *                           assigned to now
     */
    boolean reassignEngineer(Long ticketId, Long newEngineerId, Long expectedEngineerId);

    boolean updatePriority(Long ticketId, Priority priority);

    /**
     * Replaces the two SLA deadlines on a ticket still in flight.
     *
     * <p>Guarded against finished tickets: the deadlines a closed ticket was
     * judged against are part of the record and must not be rewritten.</p>
     */
    boolean updateDeadlines(Long ticketId, LocalDateTime responseDeadline,
                            LocalDateTime resolutionDeadline);

    /**
     * Writes the SLA standing computed by the SLA engine.
     *
     * @return true only when the stored value actually changed, so a monitor
     *         pass can report how many tickets moved rather than how many it
     *         looked at
     */
    boolean updateSlaStatus(Long ticketId, SLAStatus slaStatus);

    boolean updateEscalation(Long ticketId, EscalationLevel level);

    /**
     * Moves a ticket one rung up the ladder, guarded on the rung and the
     * status the caller read.
     *
     * <p>The level guard is what makes two escalations racing each other
     * safe: both read the same rung, both try to move off it, and only the
     * first matches. Without it the second would overwrite the first and
     * the trail would show two moves from the same level.</p>
     *
     * <p>The status is set to {@code ESCALATED} in the same statement. A
     * ticket already escalated is simply set to it again, because a second
     * escalation raises the level the ticket is handled at without changing
     * what state it is in.</p>
     *
     * @return false when the ticket has moved on since it was read
     */
    boolean escalate(Long ticketId, EscalationLevel expectedLevel, EscalationLevel newLevel,
                     TicketStatus expectedStatus);

    boolean recordFirstResponse(Long ticketId, LocalDateTime respondedAt);

    /**
     * Writes the resolution and moves the ticket to RESOLVED, guarded on the
     * status the caller read.
     */
    boolean resolve(Long ticketId, TicketStatus expectedStatus, ResolutionCode resolutionCode,
                    String rootCause, String resolution);

    boolean close(Long ticketId);
}
