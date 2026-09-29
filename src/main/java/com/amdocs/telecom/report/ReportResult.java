package com.amdocs.telecom.report;

import com.amdocs.telecom.model.Displayable;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * A report produced in the background, and what it cost to produce.
 *
 * <p>The timing is not decoration. The point of running reports on a pool
 * is that several slow queries can be in flight at once, and without
 * knowing how long each took there is no way to show that, or to notice
 * the day one of them becomes the reason the dashboard is slow. The
 * thread name is here for the same reason: it is the evidence that the
 * work really did happen somewhere else.</p>
 *
 * <p>Immutable, and the table it wraps is immutable too, because the
 * result crosses a thread boundary: the pool builds it and the caller
 * reads it through a {@link java.util.concurrent.Future}.</p>
 */
public final class ReportResult implements Displayable {

    private final ReportTable table;
    private final LocalDateTime finishedAt;
    private final Duration took;
    private final String producedBy;

    ReportResult(ReportTable table, LocalDateTime finishedAt, Duration took,
                 String producedBy) {
        this.table = table;
        this.finishedAt = finishedAt;
        this.took = took;
        this.producedBy = producedBy;
    }

    public ReportTable getTable() {
        return table;
    }

    public ReportKind getKind() {
        return table.getKind();
    }

    public int getRowCount() {
        return table.getRowCount();
    }

    public boolean isEmpty() {
        return table.isEmpty();
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public Duration getTook() {
        return took;
    }

    public long getTookMillis() {
        return took == null ? 0L : took.toMillis();
    }

    /**
     * The thread that produced this, which is how a console can show that
     * several reports really did run at the same time.
     */
    public String getProducedBy() {
        return producedBy;
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-30s %4d row(s) in %5d ms on %s",
                getKind().getDisplayName(), getRowCount(), getTookMillis(), producedBy);
    }

    @Override
    public String toDetailBlock() {
        return table.toDetailBlock();
    }

    @Override
    public String toString() {
        return toSummaryLine();
    }
}
