package com.amdocs.telecom.model;

import com.amdocs.telecom.model.enums.EventStatus;
import com.amdocs.telecom.model.enums.NetworkEventType;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.model.enums.Severity;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * An alarm raised by a network element.
 *
 * <p>These are the items the producer places on the blocking queue in section
 * 11. A consumer picks each one up, decides whether it warrants a ticket, and
 * records the outcome in {@code eventStatus}. Instances therefore cross
 * thread boundaries, so nothing here relies on shared mutable state beyond
 * the single owning consumer.</p>
 */
public class NetworkEvent extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String eventReference;
    private String networkNode;
    private NetworkEventType eventType;
    private Severity severity;
    private LocalDateTime eventTime;
    private EventStatus eventStatus = EventStatus.RECEIVED;
    private Region region;
    private String details;
    private Long ticketId;
    private LocalDateTime processedDate;
    private LocalDateTime createdAt;

    public NetworkEvent() {
        super();
    }

    public NetworkEvent(String eventReference, String networkNode, NetworkEventType eventType,
                        Severity severity, Region region, String details) {
        this.eventReference = eventReference;
        this.networkNode = networkNode;
        this.eventType = eventType;
        this.severity = severity;
        this.region = region;
        this.details = details;
        this.eventTime = stampNow();
    }

    public String getEventReference() {
        return eventReference;
    }

    public void setEventReference(String eventReference) {
        this.eventReference = eventReference;
    }

    public String getNetworkNode() {
        return networkNode;
    }

    public void setNetworkNode(String networkNode) {
        this.networkNode = networkNode;
    }

    public NetworkEventType getEventType() {
        return eventType;
    }

    public void setEventType(NetworkEventType eventType) {
        this.eventType = eventType;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public LocalDateTime getEventTime() {
        return eventTime;
    }

    public void setEventTime(LocalDateTime eventTime) {
        this.eventTime = eventTime;
    }

    public EventStatus getEventStatus() {
        return eventStatus;
    }

    public void setEventStatus(EventStatus eventStatus) {
        this.eventStatus = eventStatus;
    }

    public Region getRegion() {
        return region;
    }

    public void setRegion(Region region) {
        this.region = region;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public LocalDateTime getProcessedDate() {
        return processedDate;
    }

    public void setProcessedDate(LocalDateTime processedDate) {
        this.processedDate = processedDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Whether this alarm should cause a ticket to be opened automatically.
     * Both the type and the severity have to agree: a link down is worth a
     * ticket, an informational heartbeat is not.
     */
    public boolean warrantsTicket() {
        return eventType != null
                && eventType.isTicketWorthy()
                && severity != null
                && severity != Severity.INFO;
    }

    /**
     * The ticket this event produced, if it produced one.
     */
    public Optional<Long> findTicketId() {
        return Optional.ofNullable(ticketId);
    }

    public boolean isProcessed() {
        return eventStatus != null && eventStatus.isProcessed();
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-12s %-16s %-20s %-10s %-18s %s",
                Displayable.orDash(eventReference),
                Displayable.truncate(networkNode, 16),
                eventType == null ? "-" : eventType.getDisplayName(),
                severity == null ? "-" : severity.getDisplayName(),
                Displayable.formatDateTime(eventTime),
                eventStatus == null ? "-" : eventStatus.getDisplayName());
    }

    @Override
    public String toDetailBlock() {
        StringBuilder builder = new StringBuilder();
        builder.append(Displayable.labelled("Event Ref", eventReference)).append(System.lineSeparator());
        builder.append(Displayable.labelled("Node", networkNode)).append(System.lineSeparator());
        builder.append(Displayable.labelled("Type",
                eventType == null ? null : eventType.getDisplayName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Severity",
                severity == null ? null : severity.getDisplayName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Region",
                region == null ? null : region.getDisplayName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Raised at",
                Displayable.formatDateTime(eventTime))).append(System.lineSeparator());
        builder.append(Displayable.labelled("Status",
                eventStatus == null ? null : eventStatus.getDisplayName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Processed",
                Displayable.formatDateTime(processedDate))).append(System.lineSeparator());
        builder.append(Displayable.labelled("Details", details));
        return builder.toString();
    }
}
