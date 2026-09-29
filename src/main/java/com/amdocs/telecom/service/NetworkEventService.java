package com.amdocs.telecom.service;

import com.amdocs.telecom.model.NetworkEvent;
import com.amdocs.telecom.model.enums.EventStatus;
import com.amdocs.telecom.service.event.EventOutcome;

import java.util.List;
import java.util.Map;

/**
 * Network event processing from section 11 of the case study.
 *
 * <p>Section 11 describes a stream of alarms from network elements and a
 * background thread that processes them and "potentially" opens tickets.
 * This interface is the deciding half of that: what one alarm is worth. The
 * threading is
 * {@link com.amdocs.telecom.scheduler.NetworkEventProcessor}'s business, and
 * keeping the two apart is what lets the decision be checked without a
 * thread pool and the pool be checked without the database.</p>
 *
 * <p>Nothing here takes a {@link com.amdocs.telecom.security.UserSession}.
 * These alarms arrive from equipment, not from people, and the tickets they
 * raise are recorded against
 * {@link com.amdocs.telecom.service.event.TicketEvent#SYSTEM_ACTOR}.</p>
 */
public interface NetworkEventService {

    /* ---------- Taking alarms in ---------- */

    /**
     * Stores one alarm as received, without processing it.
     *
     * @throws com.amdocs.telecom.exception.DuplicateResourceException when
     *         the reference has been seen before, which is the table
     *         refusing to record the same alarm twice
     */
    NetworkEvent record(NetworkEvent event);

    /**
     * Stores many alarms in one round trip, which is how the simulator feeds
     * the queue.
     *
     * @return how many rows were written
     */
    int recordAll(List<NetworkEvent> events);

    /* ---------- Working them off ---------- */

    /**
     * Alarms nobody has dealt with yet, oldest first.
     *
     * @param limit how many to return, or zero for the default
     */
    List<NetworkEvent> pending(int limit);

    /**
     * Decides what one alarm is worth and records the answer.
     *
     * <p>Claims the event first, so of two consumers racing for the same
     * alarm exactly one does the work and the other is told it lost. The
     * claim, the ticket and the outcome are one transaction: an alarm can
     * never be marked as having raised a ticket that was not kept.</p>
     *
     * <p>Does not throw for an alarm it cannot handle. A consumer thread
     * that dies on the first awkward event stops processing everything
     * behind it, so a failure becomes {@link EventStatus#FAILED} on the row
     * and a {@link EventOutcome#isFailed()} outcome for the caller. Only a
     * failure that leaves the database unusable propagates.</p>
     */
    EventOutcome process(NetworkEvent event);

    /**
     * Works through the pending alarms on the calling thread, in order.
     *
     * <p>The single threaded path, for a console operator who wants the
     * backlog cleared now and for anything that needs the work done by the
     * time the call returns.</p>
     */
    List<EventOutcome> drainPending(int limit);

    /* ---------- Looking at the stream ---------- */

    /**
     * How many alarms sit in each processing state.
     */
    Map<EventStatus, Long> countByStatus();

    /**
     * The alarms the simulator would produce, built but not stored, so a
     * caller can feed them to a queue or store them itself.
     *
     * @param count how many to build
     */
    List<NetworkEvent> simulate(int count);
}
