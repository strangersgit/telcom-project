package com.amdocs.telecom.dao;

import com.amdocs.telecom.model.TicketStatusHistory;

import java.util.List;

/**
 * The status trail for a ticket. Append only, so there is no update path.
 */
public interface TicketStatusHistoryDAO extends GenericDAO<TicketStatusHistory, Long> {

    /**
     * Every transition for one ticket, oldest first.
     */
    List<TicketStatusHistory> findByTicketId(Long ticketId);

    /**
     * Most recent transitions across all tickets, for the audit screen.
     */
    List<TicketStatusHistory> findRecent(int limit);

    long countByTicketId(Long ticketId);
}
