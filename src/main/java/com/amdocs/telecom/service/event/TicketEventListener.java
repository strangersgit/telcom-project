package com.amdocs.telecom.service.event;

/**
 * An observer of ticket events.
 *
 * <p>Section 18 asks for the Observer pattern behind notifications. This is
 * the observer half: the ticket service announces what happened without
 * knowing, or caring, who is listening. Adding a fifth thing that should
 * happen when a ticket is resolved means writing a new listener and
 * registering it, not editing the ticket service.</p>
 *
 * <p>A listener runs inside the transaction that caused the event, so
 * anything it writes commits or rolls back with the change it describes.
 * {@link TicketEventPublisher} explains why that matters.</p>
 */
public interface TicketEventListener {

    /**
     * A short name for this listener, used in log lines and in the
     * publisher's own listing. It exists so a failure can be attributed to
     * a listener by name rather than by class.
     */
    String getName();

    /**
     * Handles one event.
     *
     * <p>Called only for event types {@link #isInterestedIn} accepted.</p>
     */
    void onTicketEvent(TicketEvent event);

    /**
     * Whether this listener wants to hear about a kind of event.
     *
     * <p>Filtering here rather than in {@link #onTicketEvent} keeps the
     * publisher honest about how many listeners actually did something,
     * which is what its return value reports. The default accepts
     * everything, which suits the audit trail; the notification listener
     * narrows it to the events section 12 asks about.</p>
     */
    default boolean isInterestedIn(TicketEventType type) {
        return true;
    }
}
