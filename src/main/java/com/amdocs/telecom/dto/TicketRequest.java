package com.amdocs.telecom.dto;

import com.amdocs.telecom.model.enums.IncidentCategory;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.model.enums.Severity;

/**
 * What somebody has to supply to raise a ticket.
 *
 * <p>Deliberately carries no customer: the service being complained about
 * belongs to exactly one customer, so the ticket service reads the customer
 * from the service rather than being told. That removes the possibility of
 * filing a ticket for one customer against another customer's line.</p>
 *
 * <p>Priority and severity are optional. Left out, the ticket service
 * derives them from the category, which is all a customer can reasonably be
 * expected to know. The service desk may set them, and section 14 gives them
 * an "Update Priority" screen for changing them later.</p>
 */
public final class TicketRequest {

    private Long serviceId;
    private IncidentCategory category;
    private String description;
    private Priority priority;
    private Severity severity;

    public TicketRequest() {
    }

    public TicketRequest(Long serviceId, IncidentCategory category, String description) {
        this.serviceId = serviceId;
        this.category = category;
        this.description = description;
    }

    public Long getServiceId() {
        return serviceId;
    }

    public TicketRequest setServiceId(Long serviceId) {
        this.serviceId = serviceId;
        return this;
    }

    public IncidentCategory getCategory() {
        return category;
    }

    public TicketRequest setCategory(IncidentCategory category) {
        this.category = category;
        return this;
    }

    public String getDescription() {
        return description;
    }

    public TicketRequest setDescription(String description) {
        this.description = description;
        return this;
    }

    /** Null when the raiser did not say, which is the normal case. */
    public Priority getPriority() {
        return priority;
    }

    public TicketRequest setPriority(Priority priority) {
        this.priority = priority;
        return this;
    }

    public Severity getSeverity() {
        return severity;
    }

    public TicketRequest setSeverity(Severity severity) {
        this.severity = severity;
        return this;
    }

    public boolean hasPriority() {
        return priority != null;
    }

    public boolean hasSeverity() {
        return severity != null;
    }

    @Override
    public String toString() {
        return "TicketRequest[serviceId=" + serviceId + ", category=" + category
                + ", priority=" + priority + ", severity=" + severity + "]";
    }
}
