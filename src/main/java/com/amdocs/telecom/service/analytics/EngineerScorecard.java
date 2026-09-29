package com.amdocs.telecom.service.analytics;

import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.enums.EngineerAvailability;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.model.enums.Specialization;

import java.util.Comparator;
import java.util.Optional;

/**
 * One engineer's load and record in a single row.
 *
 * <p>Section 16 asks for engineer workload and engineer performance as two
 * analyses, and section 18 for an engineer performance report. They are not
 * two computations: both come from the same pass over the same tickets, and
 * running that pass twice to fill two types would be work done for the sake
 * of the type system. So there is one scorecard and two orderings,
 * {@link #byWorkload()} and {@link #byPerformance()}, and a caller says
 * which question it is asking by choosing one.</p>
 *
 * <p>Engineers with nothing assigned are included, with zeroes. An engineer
 * missing from a workload report because they are idle is exactly the
 * engineer the reader was looking for.</p>
 */
public final class EngineerScorecard implements Displayable {

    private final Long engineerId;
    private final String employeeCode;
    private final String engineerName;
    private final Specialization specialization;
    private final Region region;
    private final int experienceYears;
    private final EngineerAvailability availability;
    private final long assigned;
    private final long resolved;
    private final long open;
    private final long breaches;
    private final Double averageResolutionHours;

    EngineerScorecard(Long engineerId, String employeeCode, String engineerName,
                      Specialization specialization, Region region, int experienceYears,
                      EngineerAvailability availability, long assigned, long resolved,
                      long open, long breaches, Double averageResolutionHours) {
        this.engineerId = engineerId;
        this.employeeCode = employeeCode;
        this.engineerName = engineerName;
        this.specialization = specialization;
        this.region = region;
        this.experienceYears = experienceYears;
        this.availability = availability;
        this.assigned = assigned;
        this.resolved = resolved;
        this.open = open;
        this.breaches = breaches;
        this.averageResolutionHours = averageResolutionHours;
    }

    public Long getEngineerId() {
        return engineerId;
    }

    public String getEmployeeCode() {
        return employeeCode;
    }

    public String getEngineerName() {
        return engineerName;
    }

    public Specialization getSpecialization() {
        return specialization;
    }

    public Region getRegion() {
        return region;
    }

    public int getExperienceYears() {
        return experienceYears;
    }

    public EngineerAvailability getAvailability() {
        return availability;
    }

    /** Every ticket ever put on them. */
    public long getAssigned() {
        return assigned;
    }

    public long getResolved() {
        return resolved;
    }

    /** What they are carrying now. */
    public long getOpen() {
        return open;
    }

    /** Of the ones they finished, how many finished late. */
    public long getBreaches() {
        return breaches;
    }

    public Optional<Double> findAverageResolutionHours() {
        return Optional.ofNullable(averageResolutionHours);
    }

    /**
     * Resolved on time as a percentage of resolved, empty for an engineer
     * who has not finished anything yet.
     */
    public Optional<Double> findOnTimePercent() {
        if (resolved <= 0L) {
            return Optional.empty();
        }
        long onTime = resolved - breaches;
        return Optional.of(Math.round(10000.0d * onTime / resolved) / 100.0d);
    }

    /**
     * Busiest first, which is the question "who is overloaded".
     */
    public static Comparator<EngineerScorecard> byWorkload() {
        return Comparator.comparingLong(EngineerScorecard::getOpen).reversed()
                .thenComparing(EngineerScorecard::getEmployeeCode);
    }

    /**
     * Most resolved first, then fewest breaches, which is the question
     * "who is getting the most done and getting it done on time".
     */
    public static Comparator<EngineerScorecard> byPerformance() {
        return Comparator.comparingLong(EngineerScorecard::getResolved).reversed()
                .thenComparingLong(EngineerScorecard::getBreaches)
                .thenComparing(EngineerScorecard::getEmployeeCode);
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-8s %-20s %-20s open %3d  resolved %3d  late %3d",
                Displayable.orDash(employeeCode), Displayable.truncate(engineerName, 20),
                specialization == null ? "-" : specialization.getDisplayName(),
                open, resolved, breaches);
    }

    @Override
    public String toString() {
        return employeeCode + " open=" + open + " resolved=" + resolved;
    }
}
