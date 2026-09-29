package com.amdocs.telecom.scheduler;

import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.TransactionTemplate;
import com.amdocs.telecom.dao.TroubleTicketDAO;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.enums.SLAStatus;
import com.amdocs.telecom.service.SlaService;
import com.amdocs.telecom.service.event.TicketEvent;
import com.amdocs.telecom.service.event.TicketEventPublisher;
import com.amdocs.telecom.service.sla.SlaEvaluation;
import com.amdocs.telecom.util.AppLogger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The scheduled worker section 17 calls the SLA Monitor: it looks for
 * tickets approaching or past their deadline and tells somebody.
 *
 * <h3>Two jobs, in order</h3>
 *
 * <p>First it writes the freshly computed standing into {@code sla_status}
 * for any open ticket whose stored value has fallen behind. A ticket's SLA
 * standing changes because time passed, not because anybody touched the row,
 * so without something doing this the column would only ever be right at the
 * moment it was written. Then it announces the tickets that need attention,
 * which is what turns a column into a notification.</p>
 *
 * <h3>Why it remembers what it has said</h3>
 *
 * <p>A sweep every minute would otherwise send the same warning sixty times
 * an hour, and a notification list nobody can read is a notification list
 * nobody reads. So the monitor keeps the standing it last announced per
 * ticket and speaks only when that changes: once when a ticket becomes at
 * risk, once more when it breaches. The map is a
 * {@link ConcurrentHashMap} because a console operator can drive a sweep by
 * hand while the scheduled one is running, and two sweeps sharing a plain
 * map could corrupt it.</p>
 *
 * <p>The memory is deliberately not durable. Restarting the application
 * re-announces the tickets that are still behind, which is the safe
 * direction to be wrong in: a warning repeated after a restart is noise,
 * a warning never sent is a missed deadline.</p>
 *
 * <h3>Why the sweep catches everything</h3>
 *
 * <p>{@link ScheduledExecutorService#scheduleAtFixedRate} cancels a task
 * that throws, silently. A single failed sweep would therefore stop all SLA
 * monitoring for the rest of the run with nothing in the log to say the
 * monitor had died. {@link #sweep()} catches its own failures for that
 * reason, not out of nervousness.</p>
 */
public final class SlaMonitor implements BackgroundWorker {

    public static final String WORKER_NAME = "tsatms-sla";

    private static final long DEFAULT_PERIOD_SECONDS = 60L;
    private static final long STOP_GRACE_SECONDS = 3L;

    private final SlaService sla;
    private final TroubleTicketDAO tickets;
    private final TicketEventPublisher publisher;
    private final long periodSeconds;
    private final boolean announcing;

    /** The standing last announced per ticket, so nothing is said twice. */
    private final Map<Long, SLAStatus> announced = new ConcurrentHashMap<Long, SLAStatus>();

    private final AtomicLong sweeps = new AtomicLong();
    private final AtomicLong corrected = new AtomicLong();
    private final AtomicLong warnings = new AtomicLong();
    private final AtomicLong breaches = new AtomicLong();
    private final AtomicLong suppressed = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    private volatile boolean running;
    private ScheduledExecutorService schedule;

    public SlaMonitor(SlaService sla) {
        this(sla, DAOFactory.getInstance(), TicketEventPublisher.getInstance(),
                DEFAULT_PERIOD_SECONDS, true);
    }

    /**
     * @param announcing whether to publish warnings and breaches, or only
     *                   keep {@code sla_status} current. A monitor that only
     *                   refreshes is useful where the notifications would be
     *                   noise, and it is how the verification harness
     *                   exercises the schedule without filling anybody's
     *                   inbox
     */
    public SlaMonitor(SlaService sla, DAOFactory factory, TicketEventPublisher publisher,
                      long periodSeconds, boolean announcing) {
        if (sla == null || factory == null || publisher == null) {
            throw new IllegalArgumentException(
                    "An SLA service, a DAO factory and a publisher are required");
        }
        if (periodSeconds < 1) {
            throw new IllegalArgumentException("The period must be at least a second");
        }
        this.sla = sla;
        this.tickets = factory.getTroubleTicketDAO();
        this.publisher = publisher;
        this.periodSeconds = periodSeconds;
        this.announcing = announcing;
    }

    @Override
    public String getName() {
        return WORKER_NAME;
    }

    @Override
    public synchronized void start() {
        if (running) {
            AppLogger.warn(SlaMonitor.class,
                    "The SLA monitor is already running; ignoring the second start");
            return;
        }
        running = true;
        schedule = Executors.newSingleThreadScheduledExecutor(new NamedThreads(WORKER_NAME));
        // Runs once immediately so a freshly started application does not
        // wait a whole period before noticing a ticket that is already late.
        schedule.scheduleAtFixedRate(this::sweep, 0L, periodSeconds, TimeUnit.SECONDS);
        AppLogger.info(SlaMonitor.class, "SLA monitor started, sweeping every "
                + periodSeconds + "s" + (announcing ? "" : " (refresh only)"));
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        ScheduledExecutorService current = schedule;
        schedule = null;
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            if (!current.awaitTermination(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
        AppLogger.info(SlaMonitor.class, "SLA monitor stopped. " + describe());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * One pass: correct the stored statuses, then announce what changed.
     *
     * <p>Public so a console operator can force a sweep, and so the
     * verification harness can take one deterministically instead of waiting
     * for the schedule.</p>
     *
     * @return how many tickets were announced in this pass
     */
    public int sweep() {
        try {
            corrected.addAndGet(sla.refreshStoredStatuses());
            sweeps.incrementAndGet();
            if (!announcing) {
                return 0;
            }
            return announce(sla.needingAttention());
        } catch (RuntimeException failure) {
            failures.incrementAndGet();
            AppLogger.error(SlaMonitor.class,
                    "An SLA sweep failed; the monitor carries on", failure);
            return 0;
        }
    }

    /**
     * Tells somebody about each ticket whose standing has changed since it
     * was last mentioned.
     */
    private int announce(List<SlaEvaluation> needingAttention) {
        int told = 0;
        for (SlaEvaluation verdict : needingAttention) {
            SLAStatus last = announced.get(verdict.getTicketId());
            if (last == verdict.getLiveStatus()) {
                suppressed.incrementAndGet();
                continue;
            }
            if (raise(verdict)) {
                // Recorded only after the announcement was kept, so a failed
                // notification is tried again next sweep rather than being
                // remembered as sent.
                announced.put(verdict.getTicketId(), verdict.getLiveStatus());
                told++;
            }
        }
        return told;
    }

    /**
     * Publishes one warning or breach, inside its own transaction so the
     * notification and the audit row are kept or discarded together.
     */
    private boolean raise(SlaEvaluation verdict) {
        try {
            TroubleTicket ticket = tickets.getById(verdict.getTicketId());
            TransactionTemplate.run(context -> {
                if (verdict.isBreached()) {
                    publisher.publish(TicketEvent.slaBreached(ticket, describeBreach(verdict)));
                    breaches.incrementAndGet();
                } else {
                    publisher.publish(TicketEvent.slaAtRisk(ticket, describeRemaining(verdict)));
                    warnings.incrementAndGet();
                }
            });
            return true;
        } catch (RuntimeException failure) {
            failures.incrementAndGet();
            AppLogger.error(SlaMonitor.class, "Could not raise the SLA alert for ticket "
                    + verdict.getTicketNumber(), failure);
            return false;
        }
    }

    private static String describeRemaining(SlaEvaluation verdict) {
        return verdict.findMinutesRemaining()
                .map(minutes -> minutes + " minute(s) left of the resolution window")
                .orElse("the resolution window is nearly used up");
    }

    private static String describeBreach(SlaEvaluation verdict) {
        return verdict.findMinutesRemaining()
                .map(minutes -> "overdue by " + Math.abs(minutes) + " minute(s)")
                .orElse("past the resolution deadline");
    }

    /**
     * Forgets what has been announced, so the next sweep speaks again.
     */
    public void resetMemory() {
        announced.clear();
    }

    public long getSweeps() {
        return sweeps.get();
    }

    public long getCorrected() {
        return corrected.get();
    }

    public long getWarnings() {
        return warnings.get();
    }

    public long getBreaches() {
        return breaches.get();
    }

    /** Alerts not sent because the same thing had already been said. */
    public long getSuppressed() {
        return suppressed.get();
    }

    public long getFailures() {
        return failures.get();
    }

    public int getRemembered() {
        return announced.size();
    }

    public boolean isAnnouncing() {
        return announcing;
    }

    @Override
    public String describe() {
        return String.format("%s: %d sweep(s), %d status correction(s), %d warning(s), "
                        + "%d breach(es), %d suppressed, %d failure(s)",
                WORKER_NAME, getSweeps(), getCorrected(), getWarnings(), getBreaches(),
                getSuppressed(), getFailures());
    }
}
