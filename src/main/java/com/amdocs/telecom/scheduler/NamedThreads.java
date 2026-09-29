package com.amdocs.telecom.scheduler;

import com.amdocs.telecom.util.AppLogger;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread factory that gives the background workers names and makes them
 * daemons.
 *
 * <p>Two reasons this is worth a class rather than a lambda. The log format
 * includes the thread name, so {@code tsatms-alarm-2} in a log line says
 * which worker wrote it, where {@code pool-3-thread-2} says nothing. And the
 * threads are daemons: a console application that has printed its last line
 * should exit, not hang because a scheduler is still waiting for its next
 * tick. Every worker also stops cleanly on request, so the daemon flag is
 * the backstop rather than the plan.</p>
 *
 * <p>An uncaught exception handler is attached as well. A worker that lets
 * an exception escape its own loop would otherwise die silently, and the
 * first anybody would know of it is that alarms stopped being processed.</p>
 */
public final class NamedThreads implements ThreadFactory {

    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger();

    /**
     * @param prefix names the worker, and appears in every log line the
     *               thread writes
     */
    public NamedThreads(String prefix) {
        if (prefix == null || prefix.trim().isEmpty()) {
            throw new IllegalArgumentException("A thread name prefix is required");
        }
        this.prefix = prefix.trim();
    }

    @Override
    public Thread newThread(Runnable work) {
        Thread thread = new Thread(work, prefix + "-" + counter.incrementAndGet());
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((who, failure) ->
                AppLogger.error(NamedThreads.class,
                        "Worker thread '" + who.getName() + "' died of an uncaught exception",
                        failure instanceof Exception ? (Exception) failure
                                : new IllegalStateException(failure)));
        return thread;
    }
}
