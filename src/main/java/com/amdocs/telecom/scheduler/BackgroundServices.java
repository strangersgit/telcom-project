package com.amdocs.telecom.scheduler;

import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.report.ReportGenerator;
import com.amdocs.telecom.service.NetworkEventService;
import com.amdocs.telecom.service.SlaService;
import com.amdocs.telecom.service.event.TicketEventPublisher;
import com.amdocs.telecom.service.impl.NetworkEventServiceImpl;
import com.amdocs.telecom.service.impl.SlaServiceImpl;
import com.amdocs.telecom.util.AppLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One place that starts and stops every background worker.
 *
 * <p>Without this the application would have five workers to remember, in
 * an order that matters, and every exit path would have to remember all
 * five. Miss one on shutdown and the process hangs or, worse, a sweep runs
 * against a half torn down system. So starting and stopping are each one
 * call.</p>
 *
 * <h3>Order matters, in opposite directions</h3>
 *
 * <p>Starting goes consumers before producer: the feeder must not be
 * manufacturing alarms before anything is listening. Stopping goes the
 * other way, producer first, so no new alarm arrives while the consumers
 * are draining what they have. Stopping the consumers first would leave the
 * feeder filling a queue nobody will ever read.</p>
 *
 * <h3>A singleton, like the other one-of-a-kind services</h3>
 *
 * <p>Two sets of these workers in one process would double every alarm and
 * send every notification twice, so there is one, through the same holder
 * idiom the rest of the system uses. A caller that wants an isolated set,
 * such as the verification harness, constructs the workers directly rather
 * than going through here.</p>
 */
public final class BackgroundServices {

    private final NetworkEventService eventService;
    private final NetworkEventProcessor alarmProcessor;
    private final NetworkEventFeeder alarmFeeder;
    private final SlaMonitor slaMonitor;
    private final NotificationProcessor notificationProcessor;
    private final ReportGenerator reportGenerator;

    private volatile boolean started;

    private BackgroundServices() {
        DAOFactory factory = DAOFactory.getInstance();
        this.eventService = new NetworkEventServiceImpl();
        this.alarmProcessor = new NetworkEventProcessor(eventService);
        this.alarmFeeder = new NetworkEventFeeder(eventService, alarmProcessor);
        this.slaMonitor = new SlaMonitor(new SlaServiceImpl(), factory,
                TicketEventPublisher.getInstance(), 60L, true);
        this.notificationProcessor = new NotificationProcessor();
        this.reportGenerator = new ReportGenerator();
    }

    private static final class Holder {
        private static final BackgroundServices INSTANCE = new BackgroundServices();
    }

    public static BackgroundServices getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * Starts every worker, consumers before producers.
     */
    public synchronized void startAll() {
        if (started) {
            AppLogger.warn(BackgroundServices.class, "Background services are already running");
            return;
        }
        reportGenerator.start();
        notificationProcessor.start();
        slaMonitor.start();
        alarmProcessor.start();
        alarmFeeder.start();
        started = true;
        AppLogger.info(BackgroundServices.class, "All background services are running");
    }

    /**
     * Stops every worker, producers before consumers.
     *
     * <p>Safe to call when nothing was started, because an exit path should
     * not have to check first.</p>
     */
    public synchronized void stopAll() {
        if (!started) {
            return;
        }
        alarmFeeder.stop();
        alarmProcessor.stop();
        slaMonitor.stop();
        notificationProcessor.stop();
        reportGenerator.stop();
        started = false;
        AppLogger.info(BackgroundServices.class, "All background services have stopped");
    }

    /**
     * Registers {@link #stopAll()} to run when the JVM exits.
     *
     * <p>The workers are daemon threads, so the process will end regardless.
     * The hook is about ending tidily: a consumer mid alarm gets its grace
     * period, and the closing statistics reach the log instead of
     * disappearing with the process.</p>
     */
    public void stopOnExit() {
        Runtime.getRuntime().addShutdownHook(
                new Thread(this::stopAll, "tsatms-shutdown"));
    }

    public boolean isStarted() {
        return started;
    }

    /**
     * Every worker, for a console that wants to show what is running.
     */
    public List<BackgroundWorker> workers() {
        List<BackgroundWorker> all = new ArrayList<BackgroundWorker>();
        all.add(alarmFeeder);
        all.add(alarmProcessor);
        all.add(slaMonitor);
        all.add(notificationProcessor);
        all.add(reportGenerator);
        return Collections.unmodifiableList(all);
    }

    /**
     * One line per worker, for the console.
     */
    public List<String> status() {
        List<String> lines = new ArrayList<String>();
        for (BackgroundWorker worker : workers()) {
            lines.add(String.format("%-16s %-8s %s", worker.getName(),
                    worker.isRunning() ? "running" : "stopped", worker.describe()));
        }
        return lines;
    }

    public NetworkEventService getEventService() {
        return eventService;
    }

    public NetworkEventProcessor getAlarmProcessor() {
        return alarmProcessor;
    }

    public NetworkEventFeeder getAlarmFeeder() {
        return alarmFeeder;
    }

    public SlaMonitor getSlaMonitor() {
        return slaMonitor;
    }

    public NotificationProcessor getNotificationProcessor() {
        return notificationProcessor;
    }

    public ReportGenerator getReportGenerator() {
        return reportGenerator;
    }
}
