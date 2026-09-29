package com.amdocs.telecom.service.sla;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Counts every minute, including nights, weekends and holidays.
 *
 * <p>The default for all four bands, and the only one that matches the
 * database. {@code fn_sla_deadline} adds the window with {@code DATE_ADD}
 * and {@code fn_sla_status} measures elapsed time with
 * {@code TIMESTAMPDIFF}, both of which are plain wall clock arithmetic. A
 * network operations centre works this way in any case: the network does not
 * keep office hours.</p>
 *
 * <p>Stateless, so a single shared instance serves every caller.</p>
 */
public final class ContinuousSlaClock implements SlaClock {

    /** The name this clock is selected by in {@code application.properties}. */
    public static final String NAME = "continuous";

    private static final ContinuousSlaClock INSTANCE = new ContinuousSlaClock();

    private ContinuousSlaClock() {
    }

    public static ContinuousSlaClock getInstance() {
        return INSTANCE;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Round the clock, 24 hours a day and 7 days a week";
    }

    @Override
    public LocalDateTime deadlineFrom(LocalDateTime start, int slaMinutes) {
        SlaClock.requireMoment(start, "The moment an SLA window opens");
        SlaClock.requireMinutes(slaMinutes);
        return start.plusMinutes(slaMinutes);
    }

    @Override
    public long elapsedMinutes(LocalDateTime from, LocalDateTime to) {
        SlaClock.requireMoment(from, "The start of an elapsed SLA period");
        SlaClock.requireMoment(to, "The end of an elapsed SLA period");
        if (!to.isAfter(from)) {
            return 0L;
        }
        return ChronoUnit.MINUTES.between(from, to);
    }

    @Override
    public boolean isContinuous() {
        return true;
    }

    @Override
    public String toString() {
        return NAME;
    }
}
