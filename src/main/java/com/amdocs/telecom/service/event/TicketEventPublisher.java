package com.amdocs.telecom.service.event;

import com.amdocs.telecom.service.impl.NotificationServiceImpl;
import com.amdocs.telecom.util.AppLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The subject half of the Observer pattern section 18 asks for: it holds
 * the listeners and hands each event to the ones that want it.
 *
 * <h3>Why the listener list is copy-on-write</h3>
 *
 * <p>Section 10 puts the SLA monitor on a scheduled thread and event
 * processing on a worker pool, so events will be published from several
 * threads at once while the list of listeners is only written at startup.
 * {@link CopyOnWriteArrayList} is the collection built for exactly that
 * shape: iteration never needs a lock and never throws
 * {@code ConcurrentModificationException}, at the cost of copying the array
 * when a listener is added, which happens once.</p>
 *
 * <h3>Why a failing listener stops everything</h3>
 *
 * <p>{@link #publish} lets an exception out rather than logging and
 * carrying on. That is a decision, and section 19 is the reason: the
 * assignment transaction it specifies lists "Create Notification" and
 * "Create Audit Record" among its steps and ends with "Any failure →
 * ROLLBACK". A ticket that was assigned but whose engineer was never told,
 * or whose assignment left no audit record, is worse than an assignment
 * that visibly failed and can be retried. Since listeners run inside the
 * caller's transaction, letting the exception out is what makes the
 * rollback happen.</p>
 *
 * <h3>Why the shared instance arrives ready wired</h3>
 *
 * <p>{@link #getInstance()} returns a publisher that already has the audit
 * and notification listeners registered. The alternative — an empty
 * singleton plus a wiring call at startup — fails silently if anybody
 * forgets the call: tickets would move through their lifecycle leaving no
 * audit trail at all, and nothing would say so. A publisher that cannot be
 * under-wired is worth the class knowing the two standard listeners by
 * name. Anything wanting a publisher with different listeners, a test
 * harness included, constructs an empty one instead.</p>
 */
public final class TicketEventPublisher {

    private final List<TicketEventListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Builds an empty publisher, for callers that want to choose their own
     * listeners.
     */
    public TicketEventPublisher() {
        // Nothing registered: see getInstance for the standard set.
    }

    private static final class Holder {
        private static final TicketEventPublisher INSTANCE = standard();
    }

    /**
     * The publisher the services use, carrying the standard listeners.
     */
    public static TicketEventPublisher getInstance() {
        return Holder.INSTANCE;
    }

    private static TicketEventPublisher standard() {
        TicketEventPublisher publisher = new TicketEventPublisher();
        publisher.register(new AuditTrailListener());
        publisher.register(new NotificationServiceImpl());
        return publisher;
    }

    /**
     * Adds a listener, ignoring a second registration of the same one.
     *
     * <p>Registering twice would write two audit rows for one change, so
     * the duplicate is dropped rather than trusted.</p>
     *
     * @return this publisher, so registrations can be chained
     */
    public TicketEventPublisher register(TicketEventListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("A listener is required");
        }
        if (listeners.contains(listener)) {
            AppLogger.warn(TicketEventPublisher.class, "Listener '" + listener.getName()
                    + "' is already registered; ignoring the second registration");
            return this;
        }
        listeners.add(listener);
        return this;
    }

    public boolean unregister(TicketEventListener listener) {
        return listener != null && listeners.remove(listener);
    }

    /**
     * The registered listeners, in the order they will be called.
     */
    public List<TicketEventListener> listeners() {
        return Collections.unmodifiableList(new ArrayList<>(listeners));
    }

    /**
     * Hands an event to every interested listener.
     *
     * @return how many listeners were told
     */
    public int publish(TicketEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("An event is required");
        }
        int told = 0;
        for (TicketEventListener listener : listeners) {
            if (!listener.isInterestedIn(event.getType())) {
                continue;
            }
            try {
                listener.onTicketEvent(event);
                told++;
            } catch (RuntimeException failure) {
                // Re-thrown, not swallowed: see the class comment. The log
                // line names the listener because the exception that
                // reaches the caller will be about a missing row or a
                // constraint, with nothing in it to say which listener was
                // running.
                AppLogger.error(TicketEventPublisher.class, "Listener '" + listener.getName()
                        + "' failed handling " + event + "; the transaction will roll back",
                        failure);
                throw failure;
            }
        }
        return told;
    }
}
