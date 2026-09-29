package com.amdocs.telecom.scheduler;

/**
 * One of the background workers section 17 asks for.
 *
 * <p>All four have the same shape: they are started once, they run until
 * asked to stop, they can say whether they are running, and they can
 * describe what they have done. Giving them one interface means
 * {@link BackgroundServices} can start and stop them without knowing which
 * is a scheduled sweep and which is a pool of consumers, and a dashboard can
 * list them.</p>
 *
 * <p>Implementations must make {@link #start()} and {@link #stop()} safe to
 * call twice. A console operator will do it, and a worker that threw on the
 * second stop would turn a shutdown into a stack trace.</p>
 */
public interface BackgroundWorker {

    /**
     * The worker's name, which is also the thread name prefix so a log line
     * can be traced back here.
     */
    String getName();

    /**
     * Starts the worker, or does nothing if it is already running.
     */
    void start();

    /**
     * Asks the worker to finish what it is doing and stop.
     *
     * <p>Graceful: work already taken on is completed where that is
     * possible, rather than being abandoned half done.</p>
     */
    void stop();

    boolean isRunning();

    /**
     * What this worker has done so far, in one line for a console.
     */
    String describe();
}
