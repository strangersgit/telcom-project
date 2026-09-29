package com.amdocs.telecom.scheduler;

import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.NotificationDAO;
import com.amdocs.telecom.model.Notification;
import com.amdocs.telecom.util.AppLogger;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The scheduled worker section 17 calls the NotificationProcessor: it picks
 * up messages waiting to go out and delivers them.
 *
 * <h3>What delivery means here, honestly</h3>
 *
 * <p>There is no mail server and no SMS gateway in this system, so
 * delivering a message means writing it to the log in the shape the
 * channel would take. That is the whole of the outbound side, and pretending
 * otherwise would be worse than saying so.</p>
 *
 * <p>The schema is the other half of the honesty. {@code notifications} has
 * {@code read_status}, which records whether the recipient has read a
 * message, and nothing that records whether the dispatcher has sent one.
 * Those are different facts, and there is no column for the second. So the
 * processor keeps its own record, in memory, of what it has sent, and that
 * record dies with the process: restarting the application will deliver
 * unread messages again. Marking them read instead would be worse, because
 * it would tell the recipient's unread count a lie to save the dispatcher
 * some bookkeeping. A message delivered twice is an annoyance; an unread
 * count that is wrong makes the console untrustworthy.</p>
 *
 * <h3>Why the sent set is synchronized</h3>
 *
 * <p>Only the scheduled thread sweeps, but {@link #dispatchNow()} is public
 * so a console operator can force one, which puts two threads in the same
 * set. A {@code synchronizedSet} is enough here because the whole
 * check-and-add is done inside one {@code synchronized} block: a
 * concurrent set would make each operation safe and still let two threads
 * both decide a message was unsent.</p>
 */
public final class NotificationProcessor implements BackgroundWorker {

    public static final String WORKER_NAME = "tsatms-notify";

    private static final int DEFAULT_BATCH = 50;
    private static final long DEFAULT_PERIOD_SECONDS = 30L;
    private static final long STOP_GRACE_SECONDS = 3L;

    /**
     * Beyond this the processor forgets the oldest of what it has sent.
     * A set that only ever grows is a slow leak in a process meant to run
     * all day; the cost of forgetting is at worst one message delivered
     * twice.
     */
    private static final int MEMORY_LIMIT = 5000;

    private final NotificationDAO notifications;
    private final int batchSize;
    private final long periodSeconds;

    private final Set<Long> sent = Collections.synchronizedSet(new HashSet<Long>());

    private final AtomicLong sweeps = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    private volatile boolean running;
    private ScheduledExecutorService schedule;

    public NotificationProcessor() {
        this(DAOFactory.getInstance(), DEFAULT_BATCH, DEFAULT_PERIOD_SECONDS);
    }

    public NotificationProcessor(DAOFactory factory, int batchSize, long periodSeconds) {
        if (factory == null) {
            throw new IllegalArgumentException("A DAO factory is required");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("A batch needs at least one message");
        }
        if (periodSeconds < 1) {
            throw new IllegalArgumentException("The period must be at least a second");
        }
        this.notifications = factory.getNotificationDAO();
        this.batchSize = batchSize;
        this.periodSeconds = periodSeconds;
    }

    @Override
    public String getName() {
        return WORKER_NAME;
    }

    @Override
    public synchronized void start() {
        if (running) {
            AppLogger.warn(NotificationProcessor.class,
                    "The notification processor is already running; ignoring the second start");
            return;
        }
        running = true;
        schedule = Executors.newSingleThreadScheduledExecutor(new NamedThreads(WORKER_NAME));
        schedule.scheduleAtFixedRate(this::dispatchNow, periodSeconds, periodSeconds,
                TimeUnit.SECONDS);
        AppLogger.info(NotificationProcessor.class, "Notification processor started, "
                + "delivering up to " + batchSize + " message(s) every " + periodSeconds + "s");
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
        AppLogger.info(NotificationProcessor.class, "Notification processor stopped. "
                + describe());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Delivers one batch.
     *
     * <p>Public so the console can force a delivery, and so the
     * verification harness can take a pass without waiting for the
     * schedule. Catches everything for the same reason the other scheduled
     * workers do: a task that throws is cancelled without a word, and
     * notifications would quietly stop going out.</p>
     *
     * @return how many messages went out in this pass
     */
    public int dispatchNow() {
        try {
            List<Notification> waiting = notifications.findPendingDispatch(batchSize);
            sweeps.incrementAndGet();
            int count = 0;
            for (Notification message : waiting) {
                if (claim(message.getId())) {
                    deliver(message);
                    count++;
                } else {
                    skipped.incrementAndGet();
                }
            }
            delivered.addAndGet(count);
            return count;
        } catch (RuntimeException failure) {
            failures.incrementAndGet();
            AppLogger.error(NotificationProcessor.class,
                    "A notification sweep failed; the processor carries on", failure);
            return 0;
        }
    }

    /**
     * Takes ownership of a message, or says somebody already has.
     *
     * <p>The test and the add are one operation under the lock. Split in
     * two they would let two sweeps both find the message unsent and both
     * deliver it, which is the exact bug the set exists to prevent.</p>
     */
    private boolean claim(Long notificationId) {
        if (notificationId == null) {
            return false;
        }
        synchronized (sent) {
            if (sent.size() >= MEMORY_LIMIT) {
                sent.clear();
                AppLogger.debug(NotificationProcessor.class,
                        "Cleared the delivery memory after " + MEMORY_LIMIT + " message(s)");
            }
            return sent.add(notificationId);
        }
    }

    /**
     * Where a real system would hand the message to a gateway.
     */
    private void deliver(Notification message) {
        AppLogger.info(NotificationProcessor.class, String.format(
                "Delivering to %s #%d [%s]: %s",
                message.getRecipientRole() == null ? "recipient" : message.getRecipientRole().name(),
                message.getRecipientId(), message.getNotificationType().name(),
                message.getMessage()));
    }

    /**
     * Forgets what has been delivered, so the next sweep sends it again.
     */
    public void resetMemory() {
        sent.clear();
    }

    public long getSweeps() {
        return sweeps.get();
    }

    public long getDelivered() {
        return delivered.get();
    }

    /** Messages passed over because they had already gone out this run. */
    public long getSkipped() {
        return skipped.get();
    }

    public long getFailures() {
        return failures.get();
    }

    public int getRemembered() {
        return sent.size();
    }

    @Override
    public String describe() {
        return String.format("%s: %d sweep(s), %d delivered, %d already sent, %d failure(s)",
                WORKER_NAME, getSweeps(), getDelivered(), getSkipped(), getFailures());
    }
}
