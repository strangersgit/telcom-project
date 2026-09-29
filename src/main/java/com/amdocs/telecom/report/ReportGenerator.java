package com.amdocs.telecom.report;

import com.amdocs.telecom.scheduler.BackgroundWorker;
import com.amdocs.telecom.scheduler.NamedThreads;
import com.amdocs.telecom.service.ReportService;
import com.amdocs.telecom.service.impl.ReportServiceImpl;
import com.amdocs.telecom.util.AppLogger;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs reports on a thread pool so the console does not have to wait for
 * them, which is the {@code Callable} and {@code Future} half of section
 * 17.
 *
 * <h3>Why these are the tasks that suit a pool</h3>
 *
 * <p>A report is a read that returns a value and can take a while. That is
 * precisely the shape {@link Callable} exists for, where the alarm
 * consumers are {@link Runnable} because they return nothing. It is also
 * why these tasks need no transaction and no cleanup: they touch nothing,
 * so a cancelled report leaves the database exactly as it was, and several
 * running at once cannot interfere with each other.</p>
 *
 * <p>The pool is small on purpose. Every report is a database round trip,
 * and thirty threads all waiting on the same database is not thirty times
 * faster than four; it is four times as fast with twenty six threads worth
 * of extra contention. A bounded pool also makes the queueing explicit
 * rather than leaving it to the connection layer to discover.</p>
 *
 * <h3>What the caller gets</h3>
 *
 * <p>{@link #submit(ReportKind)} hands back a {@code Future}, so the caller
 * decides whether to wait, wait with a deadline, or give up and cancel.
 * {@link #runAll(Collection)} uses {@code invokeAll}, which is the right
 * call when a dashboard needs every panel before it can draw anything: it
 * blocks once for the slowest rather than once per report. A report that
 * throws surfaces as an {@link java.util.concurrent.ExecutionException}
 * when its {@code Future} is read, which is the contract callers should
 * expect and the harness checks.</p>
 */
public final class ReportGenerator implements BackgroundWorker {

    /**
     * Where a report comes from.
     *
     * <p>Injectable for the same reason the alarm consumers take a handler:
     * the interesting properties of this class are its {@code Future}
     * semantics, and proving a timeout or a cancellation against a query
     * that returns in two milliseconds is a race, not a test. A source that
     * can be made slow or made to fail turns those into facts.</p>
     */
    public interface ReportSource {

        ReportTable build(ReportKind kind);
    }

    public static final String WORKER_NAME = "tsatms-report";

    private static final int DEFAULT_THREADS = 4;
    private static final long STOP_GRACE_SECONDS = 5L;

    private final ReportSource source;
    private final int threadCount;

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong produced = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong rowsTotal = new AtomicLong();

    private volatile boolean running;
    private ExecutorService pool;

    public ReportGenerator() {
        this(new ReportServiceImpl(), DEFAULT_THREADS);
    }

    /**
     * @param reports the service that knows how to build each report
     */
    public ReportGenerator(ReportService reports, int threadCount) {
        this(serviceSource(reports), threadCount);
    }

    public ReportGenerator(ReportSource source, int threadCount) {
        if (source == null) {
            throw new IllegalArgumentException("A report source is required");
        }
        if (threadCount < 1) {
            throw new IllegalArgumentException("At least one report thread is required");
        }
        this.source = source;
        this.threadCount = threadCount;
    }

    /**
     * The normal source: each report is built by the report service from
     * one snapshot of the tickets.
     */
    private static ReportSource serviceSource(ReportService reports) {
        if (reports == null) {
            throw new IllegalArgumentException("A report service is required");
        }
        return kind -> reports.generate(kind);
    }

    @Override
    public String getName() {
        return WORKER_NAME;
    }

    @Override
    public synchronized void start() {
        if (running) {
            AppLogger.warn(ReportGenerator.class,
                    "The report generator is already running; ignoring the second start");
            return;
        }
        running = true;
        pool = Executors.newFixedThreadPool(threadCount, new NamedThreads(WORKER_NAME));
        AppLogger.info(ReportGenerator.class, "Report generator started with "
                + threadCount + " thread(s)");
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        ExecutorService current = pool;
        pool = null;
        if (current == null) {
            return;
        }
        // Reports already submitted are allowed to finish: somebody is
        // probably holding the Future and waiting for it.
        current.shutdown();
        try {
            if (!current.awaitTermination(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
                AppLogger.warn(ReportGenerator.class, "Reports still running after "
                        + STOP_GRACE_SECONDS + "s; cancelling them");
                current.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
        AppLogger.info(ReportGenerator.class, "Report generator stopped. " + describe());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /* ---------- Submitting work ---------- */

    /**
     * Starts one report and hands back a handle to its answer.
     *
     * @return a {@code Future} whose {@code get} yields the report, or
     *         throws {@code ExecutionException} if the report failed
     */
    public Future<ReportResult> submit(ReportKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("A report kind is required");
        }
        ExecutorService current = pool;
        if (!running || current == null) {
            throw new IllegalStateException(
                    "The report generator is not running; start it before submitting reports");
        }
        submitted.incrementAndGet();
        try {
            return current.submit(taskFor(kind));
        } catch (RejectedExecutionException rejected) {
            submitted.decrementAndGet();
            throw new IllegalStateException(
                    "The report generator is shutting down and cannot take " + kind.getDisplayName(),
                    rejected);
        }
    }

    /**
     * Runs several reports at once and waits for all of them.
     *
     * <p>{@code invokeAll} blocks until the last one is done, so this costs
     * the slowest report rather than the sum of them. Results come back in
     * the order the kinds were given, which is what lets a dashboard lay
     * out its panels predictably.</p>
     *
     * @return one entry per kind; a report that failed is absent from the
     *         map and logged, so one broken panel does not empty the
     *         dashboard
     */
    public Map<ReportKind, ReportResult> runAll(Collection<ReportKind> kinds) {
        Map<ReportKind, ReportResult> answers = new EnumMap<ReportKind, ReportResult>(
                ReportKind.class);
        if (kinds == null || kinds.isEmpty()) {
            return answers;
        }
        ExecutorService current = pool;
        if (!running || current == null) {
            throw new IllegalStateException(
                    "The report generator is not running; start it before submitting reports");
        }

        List<ReportKind> ordered = new ArrayList<ReportKind>(kinds);
        List<Callable<ReportResult>> tasks =
                new ArrayList<Callable<ReportResult>>(ordered.size());
        for (ReportKind kind : ordered) {
            tasks.add(taskFor(kind));
        }
        submitted.addAndGet(tasks.size());

        try {
            List<Future<ReportResult>> futures = current.invokeAll(tasks);
            for (int index = 0; index < futures.size(); index++) {
                ReportKind kind = ordered.get(index);
                try {
                    answers.put(kind, futures.get(index).get());
                } catch (java.util.concurrent.ExecutionException failure) {
                    AppLogger.error(ReportGenerator.class, "Report " + kind.getDisplayName()
                                    + " failed; the rest of the batch stands",
                            asException(failure.getCause()));
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            AppLogger.warn(ReportGenerator.class,
                    "Interrupted while waiting for reports; returning what finished");
        }
        return answers;
    }

    /**
     * Builds the unit of work for one report.
     *
     * <p>The timing and the counters live here rather than in each report
     * so every one is measured the same way, and so adding a report costs
     * nothing in this class at all.</p>
     */
    private Callable<ReportResult> taskFor(ReportKind kind) {
        return () -> {
            long startedAt = System.nanoTime();
            try {
                ReportTable table = source.build(kind);
                Duration took = Duration.ofNanos(System.nanoTime() - startedAt);
                produced.incrementAndGet();
                rowsTotal.addAndGet(table.getRowCount());
                return new ReportResult(table, LocalDateTime.now(), took,
                        Thread.currentThread().getName());
            } catch (RuntimeException failure) {
                failed.incrementAndGet();
                AppLogger.error(ReportGenerator.class,
                        "Report " + kind.getDisplayName() + " failed", failure);
                // Rethrown so the caller's Future.get reports it. Swallowing
                // it here would hand back an empty report that looks like a
                // quiet day.
                throw failure;
            }
        };
    }

    private static Exception asException(Throwable cause) {
        if (cause instanceof Exception) {
            return (Exception) cause;
        }
        return new IllegalStateException(cause);
    }

    public long getSubmitted() {
        return submitted.get();
    }

    public long getProduced() {
        return produced.get();
    }

    public long getFailed() {
        return failed.get();
    }

    public long getRowsTotal() {
        return rowsTotal.get();
    }

    public int getThreadCount() {
        return threadCount;
    }

    @Override
    public String describe() {
        return String.format("%s: %d submitted, %d produced (%d row(s)), %d failed",
                WORKER_NAME, getSubmitted(), getProduced(), getRowsTotal(), getFailed());
    }
}
