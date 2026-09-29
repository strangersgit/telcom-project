/**
 * The Observer pattern behind notifications and the audit trail, which
 * section 18 of the case study names among the patterns to demonstrate.
 *
 * <h3>The shape</h3>
 *
 * <ul>
 *   <li>{@link com.amdocs.telecom.service.event.TicketEvent} — what
 *       happened, immutable, built through static factories
 *   <li>{@link com.amdocs.telecom.service.event.TicketEventType} — the
 *       vocabulary, carrying each event's audit action and the notification
 *       it triggers
 *   <li>{@link com.amdocs.telecom.service.event.TicketEventListener} — the
 *       observer
 *   <li>{@link com.amdocs.telecom.service.event.TicketEventPublisher} — the
 *       subject
 *   <li>{@link com.amdocs.telecom.service.event.AuditTrailListener} and
 *       {@code NotificationServiceImpl} — the two standard observers
 * </ul>
 *
 * <h3>What the ticket service kept</h3>
 *
 * <p>Two trails follow a ticket and only one of them moved here. The audit
 * rows are written by a listener; the {@code ticket_status_history} rows are
 * still written by the ticket service itself. The schema is the reason.
 * {@code ticket_status_history} has a foreign key to
 * {@code trouble_tickets} that cascades on delete, which makes it part of
 * the ticket: delete the ticket and its history goes with it.
 * {@code audit_log} deliberately has no foreign keys at all, identifying its
 * subject by type and business key so a row outlives whatever it describes.
 * One is ticket data and belongs with the write that produced it; the other
 * is a record about the system and belongs to an observer.</p>
 *
 * <h3>Why the publisher references service.impl</h3>
 *
 * <p>{@code TicketEventPublisher.getInstance()} builds the two standard
 * listeners, one of which lives in {@code service.impl}, so the two
 * packages refer to each other. The alternative was a publisher that starts
 * empty and a wiring step at startup, which fails silently when somebody
 * forgets it — tickets would move through their whole lifecycle leaving no
 * audit trail, with nothing to indicate that anything was missing. The
 * reference is the price of a publisher that cannot be under-wired.</p>
 */
package com.amdocs.telecom.service.event;
