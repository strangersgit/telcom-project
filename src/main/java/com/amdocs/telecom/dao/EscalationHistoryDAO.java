package com.amdocs.telecom.dao;

import com.amdocs.telecom.model.EscalationHistory;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Escalation trail, supporting the ladder in section 10 and the escalation
 * report in section 14.
 */
public interface EscalationHistoryDAO extends GenericDAO<EscalationHistory, Long> {

    List<EscalationHistory> findByTicketId(Long ticketId);

    List<EscalationHistory> findBetween(LocalDateTime from, LocalDateTime to);

    /**
     * Escalations the SLA monitor raised on its own rather than a person.
     */
    List<EscalationHistory> findAutoEscalated();

    long countByTicketId(Long ticketId);
}
