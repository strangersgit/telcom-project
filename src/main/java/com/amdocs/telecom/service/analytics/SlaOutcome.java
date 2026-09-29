package com.amdocs.telecom.service.analytics;

import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.enums.Priority;

import java.util.Optional;

/**
 * How one priority band did against its promise.
 *
 * <p>This is the breach analysis of section 16, and the counts are defined
 * to agree exactly with {@code vw_sla_compliance} so the two can be checked
 * against each other. In particular a ticket counts as met or breached only
 * once it has a resolution date: an open ticket that is heading for a
 * breach has not breached yet, and counting it as though it had would make
 * every report worse the longer the day went on.</p>
 *
 * <p>Which is why {@link #getStillRunning()} exists. Those tickets are in
 * the total and in neither outcome, and a report that showed met and
 * breached without saying how many were still in flight would look like it
 * had lost some.</p>
 */
public final class SlaOutcome implements Displayable {

    private final Priority priority;
    private final long total;
    private final long completed;
    private final long met;
    private final long breached;
    private final Double compliancePercent;
    private final Double averageResolutionHours;

    SlaOutcome(Priority priority, long total, long completed, long met, long breached,
               Double compliancePercent, Double averageResolutionHours) {
        this.priority = priority;
        this.total = total;
        this.completed = completed;
        this.met = met;
        this.breached = breached;
        this.compliancePercent = compliancePercent;
        this.averageResolutionHours = averageResolutionHours;
    }

    public Priority getPriority() {
        return priority;
    }

    /** Every ticket raised at this priority, whatever became of it. */
    public long getTotal() {
        return total;
    }

    /** Those now resolved or closed. */
    public long getCompleted() {
        return completed;
    }

    /** Resolved within the deadline. */
    public long getMet() {
        return met;
    }

    /** Resolved, but after the deadline had passed. */
    public long getBreached() {
        return breached;
    }

    /**
     * Tickets with no resolution date yet, so no verdict either way.
     */
    public long getStillRunning() {
        return total - met - breached;
    }

    /**
     * Met as a percentage of those decided, empty while nothing at this
     * priority has been resolved.
     *
     * <p>Empty rather than zero, because no data and perfect failure are
     * different things and a report that showed 0% for a band nobody has
     * raised a ticket in would be alarming for no reason.</p>
     */
    public Optional<Double> findCompliancePercent() {
        return Optional.ofNullable(compliancePercent);
    }

    public Optional<Double> findAverageResolutionHours() {
        return Optional.ofNullable(averageResolutionHours);
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-10s total %4d  met %4d  breached %4d  running %4d  %s",
                priority.getDisplayName(), total, met, breached, getStillRunning(),
                compliancePercent == null ? "n/a"
                        : String.format("%.2f%%", compliancePercent));
    }

    @Override
    public String toString() {
        return toSummaryLine();
    }
}
