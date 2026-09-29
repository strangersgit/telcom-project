package com.amdocs.telecom.scheduler;

import com.amdocs.telecom.model.NetworkEvent;
import com.amdocs.telecom.service.NetworkEventService;
import com.amdocs.telecom.util.AppLogger;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The producer half of section 11: manufactures alarms on a schedule and
 * hands them to the consumers.
 *
 * <h3>Why the alarms go to the table before the queue</h3>
 *
 * <p>The queue lives in memory and the process can stop. If the queue were
 * the only record of an alarm, stopping the application would lose whatever
 * had not been dealt with yet, and a network alarm is not something to lose
 * quietly. So each burst is written to {@code network_events} in one batch
 * first, read back so the rows carry their keys, and only then offered to
 * the consumers. The table is the durable inbox and the queue is the hand
 * off; an alarm the queue refuses is still RECEIVED in the table and the
 * next burst's read picks it up.</p>
 *
 * <p>That also means the consumers can claim rows, which is what stops two
 * of them ticketing the same alarm. A queue of objects with no rows behind
 * them would have nothing to claim.</p>
 */
public final class NetworkEventFeeder implements BackgroundWorker {

    public static final String WORKER_NAME = "tsatms-feeder";

    private static final int DEFAULT_BURST = 5;
    private static final long DEFAULT_PERIOD_SECONDS = 15L;
    private static final long STOP_GRACE_SECONDS = 3L;

    private final NetworkEventService service;
    private final NetworkEventProcessor processor;
    private final int burstSize;
    private final long periodSeconds;

    private final AtomicLong bursts = new AtomicLong();
    private final AtomicLong manufactured = new AtomicLong();
    private final AtomicLong handedOver = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();

    private volatile boolean running;
    private ScheduledExecutorService schedule;

    public NetworkEventFeeder(NetworkEventService service, NetworkEventProcessor processor) {
        this(service, processor, DEFAULT_BURST, DEFAULT_PERIOD_SECONDS);
    }

    public NetworkEventFeeder(NetworkEventService service, NetworkEventProcessor processor,
                              int burstSize, long periodSeconds) {
        if (service == null || processor == null) {
            throw new IllegalArgumentException("An event service and a processor are required");
        }
        if (burstSize < 1) {
            throw new IllegalArgumentException("A burst needs at least one alarm");
        }
        if (periodSeconds < 1) {
            throw new IllegalArgumentException("The period must be at least a second");
        }
        this.service = service;
        this.processor = processor;
        this.burstSize = burstSize;
        this.periodSeconds = periodSeconds;
    }

    @Override
    public String getName() {
        return WORKER_NAME;
    }

    @Override
    public synchronized void start() {
        if (running) {
            AppLogger.warn(NetworkEventFeeder.class,
                    "The alarm feeder is already running; ignoring the second start");
            return;
        }
        running = true;
        schedule = Executors.newSingleThreadScheduledExecutor(new NamedThreads(WORKER_NAME));
        schedule.scheduleAtFixedRate(this::burst, periodSeconds, periodSeconds, TimeUnit.SECONDS);
        AppLogger.info(NetworkEventFeeder.class, "Alarm feeder started: " + burstSize
                + " alarm(s) every " + periodSeconds + "s");
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
        AppLogger.info(NetworkEventFeeder.class, "Alarm feeder stopped. " + describe());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * One burst: manufacture, store, read back, hand over.
     *
     * <p>Everything is caught. {@code scheduleAtFixedRate} cancels a task
     * that throws, and it does so without saying anything, so an exception
     * escaping here would stop alarms arriving for the rest of the run with
     * no indication why. This is the one place that has to be paranoid.</p>
     *
     * @return how many alarms the consumers took, for a caller driving a
     *         burst directly rather than waiting for the schedule
     */
    public int burst() {
        try {
            List<NetworkEvent> made = service.simulate(burstSize);
            manufactured.addAndGet(made.size());
            service.recordAll(made);

            // Read back rather than trusting the batch: the rows now carry
            // their keys, and anything a previous burst could not hand over
            // is still waiting and comes along now.
            List<NetworkEvent> waiting = service.pending(burstSize * 2);
            int taken = processor.offerAll(waiting);
            handedOver.addAndGet(taken);
            refused.addAndGet(waiting.size() - taken);
            bursts.incrementAndGet();

            if (taken < waiting.size()) {
                AppLogger.warn(NetworkEventFeeder.class, "The consumers took " + taken
                        + " of " + waiting.size() + " alarm(s); the rest stay in the table");
            }
            return taken;
        } catch (RuntimeException failure) {
            AppLogger.error(NetworkEventFeeder.class,
                    "An alarm burst failed; the feeder carries on", failure);
            return 0;
        }
    }

    public long getBursts() {
        return bursts.get();
    }

    public long getManufactured() {
        return manufactured.get();
    }

    public long getHandedOver() {
        return handedOver.get();
    }

    public long getRefused() {
        return refused.get();
    }

    @Override
    public String describe() {
        return String.format("%s: %d burst(s), %d alarm(s) made, %d handed over, %d refused",
                WORKER_NAME, getBursts(), getManufactured(), getHandedOver(), getRefused());
    }
}
