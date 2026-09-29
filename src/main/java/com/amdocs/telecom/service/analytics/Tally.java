package com.amdocs.telecom.service.analytics;

import com.amdocs.telecom.model.Displayable;

import java.util.Comparator;

/**
 * One counted group: how many tickets fell into it, and what share of the
 * whole that is.
 *
 * <p>Four of the nine analyses section 16 asks for are counts by something:
 * status, priority, region and category. They differ only in what they
 * group by, so they share one result type rather than four almost identical
 * ones. The key is kept alongside its label so a caller can sort or filter
 * on the real value and still print something a person can read.</p>
 *
 * @param <T> what the tickets were grouped by
 */
public final class Tally<T> implements Displayable {

    private final T key;
    private final String label;
    private final long count;
    private final double share;

    private Tally(T key, String label, long count, double share) {
        this.key = key;
        this.label = label;
        this.count = count;
        this.share = share;
    }

    /**
     * @param total the size of the whole population, used for the share.
     *              Zero is allowed and gives a share of zero rather than a
     *              division by zero
     */
    public static <T> Tally<T> of(T key, String label, long count, long total) {
        double share = total <= 0 ? 0.0d
                : Math.round(10000.0d * count / total) / 100.0d;
        return new Tally<T>(key, label, count, share);
    }

    public T getKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }

    public long getCount() {
        return count;
    }

    /** The share of the whole, as a percentage to two decimal places. */
    public double getShare() {
        return share;
    }

    public boolean isEmpty() {
        return count == 0L;
    }

    /**
     * Largest group first, with the label breaking ties so a report of the
     * same data always comes out in the same order.
     */
    public static <T> Comparator<Tally<T>> byCountDescending() {
        return Comparator.comparingLong(Tally<T>::getCount).reversed()
                .thenComparing(Tally::getLabel);
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-22s %6d  %6.2f%%", label, count, share);
    }

    @Override
    public String toString() {
        return label + "=" + count;
    }
}
