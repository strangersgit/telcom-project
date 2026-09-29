package com.amdocs.telecom.service.event;

import com.amdocs.telecom.dao.AuditLogDAO;
import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.model.AuditLog;
import com.amdocs.telecom.validation.Validators;

/**
 * Writes one {@code audit_log} row for every ticket event.
 *
 * <p>Section 17 asks for an audit trail covering all ticket changes, with
 * the old value, the new value, who did it and when. Doing it here rather
 * than in each service method means no new operation can forget: the audit
 * row is a consequence of announcing the event, not something the author of
 * the operation has to remember.</p>
 *
 * <p>This listener takes every event type. It is the one place in the
 * system that should have no opinion about which changes matter.</p>
 */
public final class AuditTrailListener implements TicketEventListener {

    /**
     * {@code old_value}, {@code new_value} and {@code details} are each
     * VARCHAR(500) in {@code 01_schema.sql}. A description can be twice
     * that, so text is shortened to fit rather than rejected.
     */
    private static final int MAX_FIELD = 500;

    private final AuditLogDAO auditLog;

    public AuditTrailListener() {
        this(DAOFactory.getInstance());
    }

    public AuditTrailListener(DAOFactory factory) {
        this.auditLog = factory.getAuditLogDAO();
    }

    @Override
    public String getName() {
        return "audit-trail";
    }

    @Override
    public void onTicketEvent(TicketEvent event) {
        auditLog.insert(AuditLog
                .forEntity(event.getTicket(), event.getType().getAuditAction(), event.getActor())
                .withChange(fit(event.findFromValue().orElse(null)),
                        fit(event.findToValue().orElse(null)))
                .withDetails(fit(event.findNote().orElse(null))));
    }

    private static String fit(String text) {
        return Validators.shorten(text, MAX_FIELD);
    }
}
