package com.amdocs.telecom.service.sla;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Counts only the minutes inside a working day on a working weekday.
 *
 * <p>Suits the bands where the promise is a business one rather than an
 * operational one: a billing query or a cosmetic fault. A ticket raised at
 * half past five on a Friday with a four hour window is then due mid morning
 * on Monday, not before breakfast on Saturday.</p>
 *
 * <p>Immutable once built, so one instance can serve the console and the
 * background monitor at the same time.</p>
 */
public final class BusinessHoursSlaClock implements SlaClock {

    /** The name this clock is selected by in {@code application.properties}. */
    public static final String NAME = "business-hours";

    private static final int DAYS_IN_WEEK = 7;

    private final LocalTime opens;
    private final LocalTime closes;
    private final Set<DayOfWeek> workingDays;

    /**
     * @param opens       when the working day starts
     * @param closes      when it ends, which must be later the same day
     * @param workingDays the days the desk is staffed, which must not be empty
     */
    public BusinessHoursSlaClock(LocalTime opens, LocalTime closes, Set<DayOfWeek> workingDays) {
        if (opens == null || closes == null) {
            throw new IllegalArgumentException("Both ends of the working day are required");
        }
        if (!opens.isBefore(closes)) {
            throw new IllegalArgumentException("The working day must open before it closes, but "
                    + opens + " is not before " + closes);
        }
        if (workingDays == null || workingDays.isEmpty()) {
            // Without this the search for the next working moment would
            // never terminate, so it is refused at construction instead.
            throw new IllegalArgumentException("At least one working day is required");
        }
        this.opens = opens;
        this.closes = closes;
        this.workingDays = Collections.unmodifiableSet(EnumSet.copyOf(workingDays));
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Working hours only, " + opens + " to " + closes + " on " + describeDays();
    }

    public LocalTime getOpens() {
        return opens;
    }

    public LocalTime getCloses() {
        return closes;
    }

    public Set<DayOfWeek> getWorkingDays() {
        return workingDays;
    }

    /**
     * Minutes available in one working day.
     */
    public int minutesPerDay() {
        return (int) ChronoUnit.MINUTES.between(opens, closes);
    }

    /**
     * Walks forward a working day at a time, spending the window as it goes,
     * and stops on the day the remainder fits into.
     */
    @Override
    public LocalDateTime deadlineFrom(LocalDateTime start, int slaMinutes) {
        SlaClock.requireMoment(start, "The moment an SLA window opens");
        SlaClock.requireMinutes(slaMinutes);

        LocalDateTime cursor = nextWorkingMoment(start);
        long remaining = slaMinutes;

        while (remaining > 0L) {
            LocalDateTime closingTime = LocalDateTime.of(cursor.toLocalDate(), closes);
            long availableToday = ChronoUnit.MINUTES.between(cursor, closingTime);
            if (availableToday >= remaining) {
                return cursor.plusMinutes(remaining);
            }
            remaining -= availableToday;
            cursor = nextWorkingMoment(LocalDateTime.of(cursor.toLocalDate().plusDays(1), opens));
        }
        // Reached only by a window of zero minutes, which is due as soon as
        // the desk is next open.
        return cursor;
    }

    /**
     * Adds up the working portion of each day the range touches.
     */
    @Override
    public long elapsedMinutes(LocalDateTime from, LocalDateTime to) {
        SlaClock.requireMoment(from, "The start of an elapsed SLA period");
        SlaClock.requireMoment(to, "The end of an elapsed SLA period");
        if (!to.isAfter(from)) {
            return 0L;
        }

        long total = 0L;
        LocalDate day = from.toLocalDate();
        LocalDate lastDay = to.toLocalDate();

        while (!day.isAfter(lastDay)) {
            if (workingDays.contains(day.getDayOfWeek())) {
                LocalDateTime dayOpens = LocalDateTime.of(day, opens);
                LocalDateTime dayCloses = LocalDateTime.of(day, closes);
                LocalDateTime segmentStart = dayOpens.isBefore(from) ? from : dayOpens;
                LocalDateTime segmentEnd = dayCloses.isAfter(to) ? to : dayCloses;
                if (segmentEnd.isAfter(segmentStart)) {
                    total += ChronoUnit.MINUTES.between(segmentStart, segmentEnd);
                }
            }
            day = day.plusDays(1);
        }
        return total;
    }

    @Override
    public boolean isContinuous() {
        return false;
    }

    /**
     * The given moment if the desk is open then, otherwise the next moment
     * it opens.
     */
    private LocalDateTime nextWorkingMoment(LocalDateTime from) {
        LocalDateTime cursor = from;
        // A full week of days is always enough, because the constructor
        // refuses a working week with no days in it.
        for (int daysSearched = 0; daysSearched <= DAYS_IN_WEEK; daysSearched++) {
            if (workingDays.contains(cursor.getDayOfWeek())) {
                LocalTime timeOfDay = cursor.toLocalTime();
                if (timeOfDay.isBefore(opens)) {
                    return LocalDateTime.of(cursor.toLocalDate(), opens);
                }
                if (timeOfDay.isBefore(closes)) {
                    return cursor;
                }
            }
            cursor = LocalDateTime.of(cursor.toLocalDate().plusDays(1), opens);
        }
        throw new IllegalStateException("No working day found within a week of " + from);
    }

    /**
     * Renders the working week as, for example, "Mon, Tue, Wed, Thu, Fri".
     */
    private String describeDays() {
        StringBuilder builder = new StringBuilder();
        for (DayOfWeek day : DayOfWeek.values()) {
            if (workingDays.contains(day)) {
                if (builder.length() > 0) {
                    builder.append(", ");
                }
                builder.append(day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
            }
        }
        return builder.toString();
    }

    @Override
    public String toString() {
        return NAME + " (" + opens + "-" + closes + ", " + describeDays() + ")";
    }
}
