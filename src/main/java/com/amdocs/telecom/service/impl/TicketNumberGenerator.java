package com.amdocs.telecom.service.impl;

import com.amdocs.telecom.dao.TroubleTicketDAO;
import com.amdocs.telecom.exception.BusinessException;
import com.amdocs.telecom.exception.ErrorCode;
import com.amdocs.telecom.util.AppConstants;

import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Issues the ticket numbers section 5 shows as {@code TT-2026-004521}.
 *
 * <p>The number has to be unique, and from Phase 11 onwards tickets are
 * raised from more than one thread: the console operator and the background
 * event consumer both create them. Two things together make that safe.</p>
 *
 * <p>Within this process, an {@link AtomicInteger} per year hands out
 * sequence numbers, so two threads asking at the same moment get different
 * ones. The counter starts from what is actually in the table rather than
 * from zero, so restarting the application continues the year's numbering
 * instead of colliding with everything already stored.</p>
 *
 * <p>Beyond one counter, it guarantees nothing: a second instance of this
 * class, another instance of the application, or somebody inserting by hand
 * could take the number first. That is what {@code uq_tickets_number} in the
 * schema is for, and it is the only unique constraint on the table besides
 * the key, so a duplicate on insert can only mean the number was taken. The
 * ticket service treats it as a signal to resynchronise and ask again, which
 * is why {@link #resync(int)} exists. Between an in-process counter and a
 * unique constraint the number is unique without a lock being held across
 * the insert.</p>
 */
public final class TicketNumberGenerator {

    /**
     * {@code %06d} stops formatting to six digits above this, and a seventh
     * digit would make the number a different shape.
     */
    static final int MAX_SEQUENCE = 999999;

    private final TroubleTicketDAO tickets;
    private final ConcurrentMap<Integer, AtomicInteger> sequenceByYear =
            new ConcurrentHashMap<Integer, AtomicInteger>();

    public TicketNumberGenerator(TroubleTicketDAO tickets) {
        if (tickets == null) {
            throw new IllegalArgumentException("A ticket DAO is required");
        }
        this.tickets = tickets;
    }

    /**
     * The next number for the current year.
     */
    public String next() {
        return nextFor(LocalDate.now().getYear());
    }

    public String nextFor(int year) {
        int sequence = counterFor(year).incrementAndGet();
        if (sequence > MAX_SEQUENCE) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Ticket numbering for " + year + " is exhausted at " + MAX_SEQUENCE
                            + " tickets. The number format needs widening.");
        }
        return String.format(AppConstants.TICKET_NUMBER_FORMAT,
                AppConstants.TICKET_NUMBER_PREFIX, year, sequence);
    }

    /**
     * Forgets the cached counter so the next request reads the table again.
     *
     * <p>Called after a duplicate key, where the stored numbers have moved
     * on without this counter hearing about it, and after a rolled back
     * transaction, where the opposite happened and the counter is ahead of
     * what was kept.</p>
     */
    public void resync(int year) {
        sequenceByYear.remove(year);
    }

    public void resync() {
        resync(LocalDate.now().getYear());
    }

    private AtomicInteger counterFor(int year) {
        AtomicInteger counter = sequenceByYear.get(year);
        if (counter != null) {
            return counter;
        }
        // Two threads can both find nothing and both query. That costs one
        // extra read; putIfAbsent decides which counter everybody then
        // shares, so it cannot hand out the same sequence twice.
        AtomicInteger seeded = new AtomicInteger(tickets.findHighestSequenceForYear(year));
        AtomicInteger existing = sequenceByYear.putIfAbsent(year, seeded);
        return existing == null ? seeded : existing;
    }
}
