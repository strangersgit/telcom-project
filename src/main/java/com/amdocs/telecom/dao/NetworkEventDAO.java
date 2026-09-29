package com.amdocs.telecom.dao;

import com.amdocs.telecom.model.NetworkEvent;
import com.amdocs.telecom.model.enums.EventStatus;
import com.amdocs.telecom.model.enums.NetworkEventType;
import com.amdocs.telecom.model.enums.Severity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Network alarms feeding the producer and consumer pipeline in section 11.
 */
public interface NetworkEventDAO extends GenericDAO<NetworkEvent, Long> {

    Optional<NetworkEvent> findByReference(String eventReference);

    List<NetworkEvent> findByStatus(EventStatus status);

    List<NetworkEvent> findByType(NetworkEventType eventType);

    List<NetworkEvent> findBySeverity(Severity severity);

    List<NetworkEvent> findByNode(String networkNode);

    /**
     * Events the consumers have not dealt with yet, oldest first.
     */
    List<NetworkEvent> findPending(int limit);

    /**
     * Claims an event for processing, but only if it is still in the state
     * the caller saw. Two consumers racing for the same event means exactly
     * one gets a true back, which is what stops a duplicate ticket.
     */
    boolean claimForProcessing(Long eventId, EventStatus expectedStatus);

    /**
     * Records the outcome once a consumer has finished with an event.
     */
    boolean markProcessed(Long eventId, EventStatus outcome, Long ticketId);

    List<NetworkEvent> findBetween(LocalDateTime from, LocalDateTime to);

    /**
     * Writes many alarms in one round trip, used by the event simulator.
     */
    int insertBatch(List<NetworkEvent> events);
}
