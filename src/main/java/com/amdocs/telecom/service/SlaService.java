package com.amdocs.telecom.service;

import com.amdocs.telecom.dto.SlaComplianceDTO;
import com.amdocs.telecom.model.SLAConfiguration;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.sla.SlaClock;
import com.amdocs.telecom.service.sla.SlaEvaluation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * SLA management from section 8 of the case study.
 *
 * <p>Three jobs. It stamps the response and resolution deadlines on a ticket
 * when it is raised, using the window configured for the ticket's priority
 * band and the counting rule chosen for that band. It judges a ticket's
 * standing at any moment, returning within SLA, at risk or breached. And it
 * lets a network manager retune a band's windows without a rebuild.</p>
 *
 * <p>The deadlines are fixed when the ticket is raised and are not moved
 * afterwards, even while a ticket waits on the customer. A promise that
 * slides whenever the operator is waiting for something is not a promise the
 * customer can rely on, so the pause in
 * {@link com.amdocs.telecom.model.enums.TicketStatus#isSlaClockRunning()}
 * governs who gets warned, not when the deadline falls.</p>
 */
public interface SlaService {

    /* ---------- Configured windows ---------- */

    /**
     * The windows for one band.
     *
     * @throws com.amdocs.telecom.exception.BusinessException when no active
     *         row exists for the band, because guessing a window would mean
     *         inventing a promise
     */
    SLAConfiguration configurationFor(Priority priority);

    /**
     * Every active band, most urgent first.
     */
    Map<Priority, SLAConfiguration> configurations();

    /**
     * Drops the cached windows so the next read comes from the table. Called
     * after a retune, and available to an operator who has edited the table
     * directly.
     */
    void reload();

    /**
     * The counting rule in force for a band.
     */
    SlaClock clockFor(Priority priority);

    /**
     * The bands and their windows, rendered for the console.
     */
    String describeBands();

    /* ---------- Deadlines ---------- */

    LocalDateTime responseDeadlineFor(Priority priority, LocalDateTime raisedAt);

    LocalDateTime resolutionDeadlineFor(Priority priority, LocalDateTime raisedAt);

    /**
     * Sets both deadlines on a ticket about to be inserted.
     *
     * <p>Fills in the raise date first if the caller has not, so the
     * deadlines and the date they were derived from cannot disagree. A ticket
     * inserted without deadlines still gets them from
     * {@code trg_tickets_before_insert}, but only at the round the clock
     * window, so anything raised through the application comes here first.</p>
     */
    void stampDeadlines(TroubleTicket ticket);

    /**
     * Recomputes and persists the deadlines of an existing open ticket,
     * which is what a change of priority requires.
     *
     * @return false when the ticket has already finished, since a closed
     *         ticket's deadlines are history
     */
    boolean recalculateDeadlines(Long ticketId);

    /* ---------- Standing ---------- */

    /**
     * Judges one ticket as at this moment.
     */
    SlaEvaluation evaluate(TroubleTicket ticket);

    SlaEvaluation evaluateByTicketNumber(String ticketNumber);

    /**
     * Judges every ticket that has not yet finished.
     */
    List<SlaEvaluation> evaluateOpen();

    /**
     * Open tickets past their resolution deadline, worst overrun first.
     */
    List<SlaEvaluation> breached();

    /**
     * Open tickets that have consumed the configured fraction of their
     * window but have not yet passed the deadline, closest to it first.
     */
    List<SlaEvaluation> atRisk();

    /**
     * Open tickets whose deadline falls inside the next given number of
     * minutes, soonest first.
     */
    List<SlaEvaluation> dueWithin(int minutes);

    /**
     * Open tickets the monitor should raise with somebody, and the input to
     * the escalation phase.
     */
    List<SlaEvaluation> needingAttention();

    /**
     * Writes the freshly computed standing into {@code sla_status} for any
     * open ticket whose stored value has fallen behind.
     *
     * <p>This is what the scheduled monitor calls. Only open tickets are
     * touched: a finished ticket's standing was settled when it was resolved
     * and cannot change afterwards.</p>
     *
     * @return how many rows were corrected
     */
    int refreshStoredStatuses();

    /* ---------- Reporting ---------- */

    /**
     * Compliance per band, read from {@code vw_sla_compliance} so the figures
     * match anything else querying the view.
     */
    List<SlaComplianceDTO> compliance();

    /* ---------- Administration ---------- */

    /**
     * Changes a band's windows.
     *
     * <p>Existing tickets keep the deadlines they were given; retuning a band
     * changes what is promised from now on, not what was promised then.</p>
     *
     * @throws com.amdocs.telecom.exception.AuthorizationException when the
     *         session may not manage SLA configuration
     * @throws com.amdocs.telecom.exception.ValidationException when the
     *         windows are not usable
     */
    void retuneWindows(UserSession actor, Priority priority,
                       int responseMinutes, int resolutionMinutes);
}
