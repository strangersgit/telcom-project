package com.amdocs.telecom.service.sla;

import java.time.LocalDateTime;

/**
 * How the minutes of an SLA window are counted.
 *
 * <p>Section 8 of the case study gives each priority band a response window
 * and a resolution window in minutes, but it does not say which minutes
 * count. Round the clock is right for a network outage, because a link down
 * at three in the morning is down at three in the morning. It is wrong for a
 * billing query raised at six on a Friday evening, which no reasonable
 * operator promises to answer before Monday.</p>
 *
 * <p>Rather than bury that difference in an {@code if} inside the SLA
 * service, each counting rule is a separate implementation of this interface
 * and the band chooses which one it uses. This is the Strategy pattern, the
 * fourth of the design patterns the case study asks for after the Singleton
 * in {@link com.amdocs.telecom.util.ConfigLoader}, the DAO layer, and the
 * factory in {@link com.amdocs.telecom.dao.DAOFactory}.</p>
 *
 * <p>Implementations must be immutable and safe to share between threads:
 * the background SLA monitor added in the multithreading phase evaluates
 * tickets while the console is still being used.</p>
 */
public interface SlaClock {

    /**
     * Short identifier used in configuration and on screen.
     */
    String getName();

    /**
     * One line explaining which minutes this clock counts.
     */
    String getDescription();

    /**
     * The moment an SLA window that opened at {@code start} runs out.
     *
     * @param start      when the ticket was raised
     * @param slaMinutes the window from the SLA configuration
     * @return the deadline, never null
     */
    LocalDateTime deadlineFrom(LocalDateTime start, int slaMinutes);

    /**
     * How many of this clock's minutes separate two moments.
     *
     * <p>Never negative: a range that ends before it starts has no elapsed
     * time rather than a negative amount of it.</p>
     */
    long elapsedMinutes(LocalDateTime from, LocalDateTime to);

    /**
     * Whether every minute counts.
     *
     * <p>The database computes SLA figures independently in
     * {@code fn_sla_status}, using plain wall clock arithmetic. A continuous
     * clock therefore agrees with the database exactly, and the verification
     * harness asserts that it does. A clock that skips nights and weekends
     * cannot agree on the at-risk percentage, so this method lets callers
     * say which figure is authoritative instead of leaving the discrepancy
     * to be discovered.</p>
     */
    boolean isContinuous();

    /**
     * How much of a window has been used up, as a fraction of it.
     *
     * <p>Above 1.0 means the deadline has passed. Both the elapsed time and
     * the size of the window are measured with this clock, so the fraction
     * is meaningful whichever counting rule is in force.</p>
     */
    default double consumedFraction(LocalDateTime start, LocalDateTime deadline,
                                    LocalDateTime upTo) {
        long window = elapsedMinutes(start, deadline);
        if (window <= 0L) {
            // A window of no length is used up the moment it opens.
            return 1.0d;
        }
        return (double) elapsedMinutes(start, upTo) / (double) window;
    }

    /**
     * Guards against a null moment, which would otherwise surface much later
     * as a deadline of null on a ticket that appears to have no SLA at all.
     */
    static LocalDateTime requireMoment(LocalDateTime value, String what) {
        if (value == null) {
            throw new IllegalArgumentException(what + " must not be null");
        }
        return value;
    }

    static int requireMinutes(int slaMinutes) {
        if (slaMinutes < 0) {
            throw new IllegalArgumentException(
                    "An SLA window cannot be negative but was " + slaMinutes);
        }
        return slaMinutes;
    }
}
