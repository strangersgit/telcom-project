package com.amdocs.telecom.service;

import com.amdocs.telecom.dto.TicketDetailDTO;
import com.amdocs.telecom.dto.TicketRequest;
import com.amdocs.telecom.model.Feedback;
import com.amdocs.telecom.model.TelecomService;
import com.amdocs.telecom.model.TicketStatusHistory;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.model.enums.ResolutionCode;
import com.amdocs.telecom.model.enums.TicketStatus;
import com.amdocs.telecom.security.UserSession;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Everything that happens to a ticket between being raised and being closed.
 *
 * <p>Two rules shape this interface. Every method takes the session acting,
 * because section 3's role matrix is not advisory: who may do a thing is part
 * of the operation, not a check a caller can forget. And there is one method
 * per kind of change rather than one general purpose status setter, because
 * the columns that change together have to be written together. Resolving a
 * ticket sets a code, a root cause, a resolution and a timestamp alongside
 * the status, and the schema's {@code chk_tickets_resolved_complete} refuses
 * a resolved ticket missing any of them.</p>
 *
 * <p>Every mutation runs as one transaction covering the ticket row, its
 * status history entry and its audit entry. A ticket whose status moved
 * without the trail recording it would make section 17's audit requirement
 * worthless, so the two either both happen or neither does.</p>
 */
public interface TicketService {

    /* ---------- Raising ---------- */

    /**
     * Raises a ticket, as section 13's "Raise New Ticket" and section 14's
     * equivalent for the service desk.
     *
     * <p>The customer comes from the service being complained about rather
     * than from the caller. Priority and severity are derived when the
     * raiser did not supply them.</p>
     *
     * @return the stored ticket, carrying its generated number and both SLA
     *         deadlines
     */
    TroubleTicket raise(UserSession actor, TicketRequest request);

    /**
     * The services a ticket may be raised against, which is what a raise
     * screen has to offer before it can ask anything else.
     */
    List<TelecomService> ticketableServicesFor(UserSession actor, Long customerId);

    /* ---------- Reading ---------- */

    /**
     * One ticket with its customer, service and engineer resolved and its
     * SLA standing computed, which is section 13's "Track Ticket".
     */
    TicketDetailDTO track(UserSession actor, String ticketNumber);

    /**
     * The raw row, for callers that need the foreign keys rather than a
     * screen.
     */
    TroubleTicket require(UserSession actor, String ticketNumber);

    List<TicketDetailDTO> listForCustomer(UserSession actor, Long customerId);

    List<TicketDetailDTO> listAssignedTo(UserSession actor, Long engineerId);

    /**
     * Everything not yet finished, which is the service desk and manager
     * work list.
     */
    List<TroubleTicket> listOpen(UserSession actor);

    /**
     * Section 13's "View Ticket History": the status trail, oldest first.
     */
    List<TicketStatusHistory> historyFor(UserSession actor, String ticketNumber);

    /**
     * Where this ticket may go next, so a menu can offer only the moves that
     * will be accepted.
     */
    Set<TicketStatus> nextStatesFor(UserSession actor, String ticketNumber);

    /* ---------- Working ---------- */

    /**
     * Moves a ticket between working states.
     *
     * <p>Handles {@code IN_PROGRESS} and {@code PENDING_CUSTOMER} only.
     * Assignment, escalation, resolution, closure and cancellation each have
     * their own method because each writes more than the status, and a
     * general setter would let a caller reach those states with the
     * accompanying columns left empty.</p>
     */
    void changeStatus(UserSession actor, String ticketNumber, TicketStatus target,
                      String remarks);

    /**
     * Records the root cause while diagnosis is still going on, which
     * section 10 lists separately from resolving.
     */
    void recordDiagnosis(UserSession actor, String ticketNumber, String rootCause);

    /**
     * Resolves the ticket and settles its final SLA standing.
     */
    void resolve(UserSession actor, String ticketNumber, ResolutionCode code, String rootCause,
                 String resolution);

    void close(UserSession actor, String ticketNumber, String remarks);

    void cancel(UserSession actor, String ticketNumber, String reason);

    /**
     * Brings a resolved ticket back into progress because the fix did not
     * hold, clearing the resolution that did not work while keeping it in
     * the status trail.
     */
    void reopen(UserSession actor, String ticketNumber, String reason);

    /**
     * Section 14's "Update Priority". Moves the SLA deadlines to the new
     * band, because a promise the ticket is no longer being judged against
     * is not a promise.
     */
    void updatePriority(UserSession actor, String ticketNumber, Priority priority,
                        String reason);

    /**
     * Stamps the first response, which is what the response half of the SLA
     * is measured against.
     *
     * @return false when a response was already recorded, since the first
     *         one is the one that counts
     */
    boolean recordFirstResponse(UserSession actor, String ticketNumber);

    /* ---------- Feedback ---------- */

    /**
     * Section 13's "Submit Feedback", available once the ticket has been
     * dealt with.
     */
    Feedback submitFeedback(UserSession actor, String ticketNumber, int rating,
                            String comments);

    Optional<Feedback> feedbackFor(UserSession actor, String ticketNumber);
}
