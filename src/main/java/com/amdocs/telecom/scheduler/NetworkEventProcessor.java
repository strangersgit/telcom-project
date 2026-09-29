package com.amdocs.telecom.scheduler;

import com.amdocs.telecom.model.NetworkEvent;
import com.amdocs.telecom.service.NetworkEventService;
import com.amdocs.telecom.service.event.EventOutcome;
import com.amdocs.telecom.util.AppLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The consumer half of section 11: a pool of threads taking alarms off a
 * blocking queue and deciding what each is worth.
 *
 * <h3>Why the queue is bounded</h3>
 *
 * <p>An unbounded queue turns a network storm into an out of memory error.
 * A fibre cut can emit alarms faster than any consumer can decide about
 * them, and a queue that accepts everything just moves the failure from the
 * producer to the heap. So the queue has a fixed capacity and
 * {@link #offer(NetworkEvent)} returns false when it is full: the producer
 * is told, the rejection is counted, and the alarm stays in the table as
 * RECEIVED for a later pass to pick up. Back pressure that is visible is
 * better than back pressure that is not.</p>
 *
 * <h3>How stopping works</h3>
 *
 * <p>Consumers poll with a timeout rather than blocking forever in
 * {@code take()}. That costs a wakeup every second or so and buys two
 * things: a consumer notices {@link #stop()} without needing to be
 * interrupted, and the queue can be drained before the pool shuts down, so
 * an alarm already accepted is not silently dropped. Interruption is still
 * handled, because {@code shutdownNow} is the fallback when a consumer is
 * wedged, and the handler restores the interrupt flag rather than
 * swallowing it: the thread is ending, and the next layer up has a right to
 * know why.</p>
 *
 * <h3>Where the synchronization is</h3>
 *
 * <p>The statistics are atomic counters, because they are written by every
 * consumer and read by whoever is watching. The count of accepted but
 * unfinished alarms is different: {@link #awaitIdle(long)} has to block
 * until it reaches zero, which needs a monitor rather than a counter, so it
 * is guarded by {@link #pendingLock} and consumers notify that lock as they
 * finish. Incrementing it inside the same lock that puts the alarm on the
 * queue is what makes the two consistent: there is no instant at which an
 * alarm is neither queued nor counted, which is exactly the window an
 * {@code awaitIdle} built on counters alone would return early in.</p>
 */
public final class NetworkEventProcessor implements BackgroundWorker {

    /** What a consumer does with one alarm. */
    public interface AlarmHandler {

        /**
         * @return what became of the alarm, never null
         */
        EventOutcome handle(NetworkEvent event);
    }

    public static final String WORKER_NAME = "tsatms-alarm";

    private static final int DEFAULT_CAPACITY = 256;
    private static final int DEFAULT_CONSUMERS = 3;
    private static final long POLL_MILLIS = 250L;
    private static final long STOP_GRACE_SECONDS = 5L;

    private final BlockingQueue<NetworkEvent> queue;
    private final AlarmHandler handler;
    private final int consumerCount;

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong ticketsRaised = new AtomicLong();
    private final AtomicLong correlated = new AtomicLong();
    private final AtomicLong ignored = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong lostClaims = new AtomicLong();

    /** Guards {@link #pending} and is the monitor {@link #awaitIdle} waits on. */
    private final Object pendingLock = new Object();

    private int pending;

    private volatile boolean running;
    private ExecutorService consumers;

    public NetworkEventProcessor(NetworkEventService service) {
        this(handlerFor(service), DEFAULT_CONSUMERS, DEFAULT_CAPACITY);
    }

    /**
     * @param handler       what to do with each alarm, which the verification
     *                      harness replaces to prove a failing handler does
     *                      not take the pool down with it
     * @param consumerCount how many threads take alarms off the queue
     * @param capacity      how many alarms the queue will hold before it
     *                      starts refusing them
     */
    public NetworkEventProcessor(AlarmHandler handler, int consumerCount, int capacity) {
        if (handler == null) {
            throw new IllegalArgumentException("An alarm handler is required");
        }
        if (consumerCount < 1) {
            throw new IllegalArgumentException("At least one consumer is required");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("The queue needs room for at least one alarm");
        }
        this.handler = handler;
        this.consumerCount = consumerCount;
        this.queue = new ArrayBlockingQueue<NetworkEvent>(capacity);
    }

    private static AlarmHandler handlerFor(NetworkEventService service) {
        if (service == null) {
            throw new IllegalArgumentException("A network event service is required");
        }
        return service::process;
    }

    /* ---------- Lifecycle ---------- */

    @Override
    public String getName() {
        return WORKER_NAME;
    }

    @Override
    public synchronized void start() {
        if (running) {
            AppLogger.warn(NetworkEventProcessor.class,
                    "The alarm processor is already running; ignoring the second start");
            return;
        }
        running = true;
        consumers = Executors.newFixedThreadPool(consumerCount, new NamedThreads(WORKER_NAME));
        for (int index = 0; index < consumerCount; index++) {
            consumers.submit(this::consume);
        }
        AppLogger.info(NetworkEventProcessor.class, "Alarm processor started with "
                + consumerCount + " consumer(s) and room for " + capacity() + " alarm(s)");
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        ExecutorService pool = consumers;
        consumers = null;
        if (pool == null) {
            return;
        }
        // shutdown rather than shutdownNow: the consumers are polling with a
        // timeout and will see running go false, finish the alarm in hand and
        // drain what is queued. shutdownNow is the fallback for one that does
        // not.
        pool.shutdown();
        try {
            if (!pool.awaitTermination(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
                AppLogger.warn(NetworkEventProcessor.class, "Alarm consumers did not stop within "
                        + STOP_GRACE_SECONDS + "s; interrupting them");
                pool.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        AppLogger.info(NetworkEventProcessor.class, "Alarm processor stopped. " + describe());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /* ---------- The producer's side ---------- */

    /**
     * Offers one alarm to the consumers.
     *
     * @return false when the queue is full, which leaves the alarm where it
     *         is rather than losing it
     */
    public boolean offer(NetworkEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("An alarm is required");
        }
        synchronized (pendingLock) {
            if (!queue.offer(event)) {
                rejected.incrementAndGet();
                return false;
            }
            pending++;
        }
        accepted.incrementAndGet();
        return true;
    }

    /**
     * Offers a batch and says how many were taken.
     */
    public int offerAll(List<NetworkEvent> batch) {
        if (batch == null) {
            return 0;
        }
        int taken = 0;
        for (NetworkEvent event : batch) {
            if (offer(event)) {
                taken++;
            }
        }
        return taken;
    }

    /* ---------- The consumers' side ---------- */

    /**
     * One consumer's whole life.
     */
    private void consume() {
        AppLogger.debug(NetworkEventProcessor.class, "Alarm consumer ready");
        try {
            while (running || !queue.isEmpty()) {
                NetworkEvent event = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (event == null) {
                    continue;
                }
                handleOne(event);
            }
        } catch (InterruptedException interrupted) {
            // Ending because somebody asked. Restoring the flag is what tells
            // the pool this thread stopped for that reason rather than
            // finishing its work.
            Thread.currentThread().interrupt();
            AppLogger.debug(NetworkEventProcessor.class, "Alarm consumer interrupted; stopping");
        }
    }

    /**
     * Deals with one alarm, and survives whatever the handler does.
     *
     * <p>Section 11 lists exception handling among the things this
     * demonstrates, and this is where it lives. A handler that throws must
     * cost one alarm, not the consumer and everything still queued behind
     * it.</p>
     */
    private void handleOne(NetworkEvent event) {
        try {
            EventOutcome outcome = handler.handle(event);
            tally(outcome);
        } catch (RuntimeException failure) {
            failures.incrementAndGet();
            AppLogger.error(NetworkEventProcessor.class, "Consumer failed on alarm "
                    + event.getEventReference() + "; carrying on with the rest", failure);
        } finally {
            completed.incrementAndGet();
            synchronized (pendingLock) {
                pending--;
                pendingLock.notifyAll();
            }
        }
    }

    private void tally(EventOutcome outcome) {
        if (outcome == null) {
            failures.incrementAndGet();
            return;
        }
        if (!outcome.isClaimed()) {
            lostClaims.incrementAndGet();
            return;
        }
        if (outcome.isFailed()) {
            failures.incrementAndGet();
        } else if (outcome.isTicketRaised()) {
            ticketsRaised.incrementAndGet();
        } else if (outcome.findTicketId().isPresent()) {
            correlated.incrementAndGet();
        } else {
            ignored.incrementAndGet();
        }
    }

    /* ---------- Waiting for quiet ---------- */

    /**
     * Blocks until every accepted alarm has been dealt with.
     *
     * <p>What makes a concurrent pipeline testable. Without it a check would
     * have to sleep and hope, which passes on a fast machine and fails on a
     * loaded one; with it the check waits for the condition it actually
     * cares about and reports honestly when the condition never arrives.</p>
     *
     * @return true when the pipeline went quiet, false on timeout
     */
    public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (pendingLock) {
            while (pending > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                pendingLock.wait(remaining);
            }
            return true;
        }
    }

    /* ---------- What it has done ---------- */

    public long getAccepted() {
        return accepted.get();
    }

    public long getRejected() {
        return rejected.get();
    }

    public long getCompleted() {
        return completed.get();
    }

    public long getTicketsRaised() {
        return ticketsRaised.get();
    }

    public long getCorrelated() {
        return correlated.get();
    }

    public long getIgnored() {
        return ignored.get();
    }

    public long getFailures() {
        return failures.get();
    }

    public long getLostClaims() {
        return lostClaims.get();
    }

    /** How many alarms are accepted but not yet dealt with. */
    public int getPending() {
        synchronized (pendingLock) {
            return pending;
        }
    }

    public int getConsumerCount() {
        return consumerCount;
    }

    public int capacity() {
        return queue.size() + queue.remainingCapacity();
    }

    /**
     * The alarms still waiting, for a console that wants to show the backlog.
     */
    public List<NetworkEvent> queued() {
        return new ArrayList<NetworkEvent>(queue);
    }

    @Override
    public String describe() {
        return String.format(
                "%s: accepted %d, completed %d (%d ticket(s), %d correlated, %d ignored, "
                        + "%d failed, %d lost claim), rejected %d, pending %d",
                WORKER_NAME, getAccepted(), getCompleted(), getTicketsRaised(), getCorrelated(),
                getIgnored(), getFailures(), getLostClaims(), getRejected(), getPending());
    }
}
