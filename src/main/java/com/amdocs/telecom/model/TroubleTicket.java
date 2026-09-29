package com.amdocs.telecom.model;

import com.amdocs.telecom.model.enums.EscalationLevel;
import com.amdocs.telecom.model.enums.IncidentCategory;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.model.enums.ResolutionCode;
import com.amdocs.telecom.model.enums.SLAStatus;
import com.amdocs.telecom.model.enums.Severity;
import com.amdocs.telecom.model.enums.TicketStatus;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Optional;

/**
 * The central entity: a reported fault against a customer's service.
 *
 * <p>Holds foreign keys rather than object references. Screens that need the
 * customer name or engineer code alongside the ticket use the joined
 * projection in the DTO package, which keeps this entity a faithful mirror of
 * its table and avoids dragging half the schema into memory on every read.</p>
 */
public class TroubleTicket extends TimestampedEntity implements Auditable {

    private static final long serialVersionUID = 1L;

    private String ticketNumber;
    private Long customerId;
    private Long serviceId;
    private IncidentCategory category;
    private String description;
    private Priority priority;
    private Severity severity;
    private TicketStatus status = TicketStatus.OPEN;
    private Long assignedEngineerId;
    private EscalationLevel escalationLevel = EscalationLevel.ENGINEER;
    private SLAStatus slaStatus = SLAStatus.WITHIN_SLA;

    private LocalDateTime createdDate;
    private LocalDateTime assignedDate;
    private LocalDateTime slaResponseDeadline;
    private LocalDateTime slaDeadline;
    private LocalDateTime firstResponseDate;
    private LocalDateTime resolutionDate;
    private LocalDateTime closedDate;

    private String rootCause;
    private String resolution;
    private ResolutionCode resolutionCode;

    private boolean autoCreated;
    private String createdBy;

    public TroubleTicket() {
        super();
    }

    public TroubleTicket(String ticketNumber, Long customerId, Long serviceId,
                         IncidentCategory category, String description,
                         Priority priority, Severity severity, String createdBy) {
        this.ticketNumber = ticketNumber;
        this.customerId = customerId;
        this.serviceId = serviceId;
        this.category = category;
        this.description = description;
        this.priority = priority;
        this.severity = severity;
        this.createdBy = createdBy;
        this.createdDate = stampNow();
    }

    /* ---------- Accessors ---------- */

    public String getTicketNumber() {
        return ticketNumber;
    }

    public void setTicketNumber(String ticketNumber) {
        this.ticketNumber = ticketNumber;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public Long getServiceId() {
        return serviceId;
    }

    public void setServiceId(Long serviceId) {
        this.serviceId = serviceId;
    }

    public IncidentCategory getCategory() {
        return category;
    }

    public void setCategory(IncidentCategory category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Priority getPriority() {
        return priority;
    }

    public void setPriority(Priority priority) {
        this.priority = priority;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public void setStatus(TicketStatus status) {
        this.status = status;
    }

    public Long getAssignedEngineerId() {
        return assignedEngineerId;
    }

    public void setAssignedEngineerId(Long assignedEngineerId) {
        this.assignedEngineerId = assignedEngineerId;
    }

    public EscalationLevel getEscalationLevel() {
        return escalationLevel;
    }

    public void setEscalationLevel(EscalationLevel escalationLevel) {
        this.escalationLevel = escalationLevel;
    }

    public SLAStatus getSlaStatus() {
        return slaStatus;
    }

    public void setSlaStatus(SLAStatus slaStatus) {
        this.slaStatus = slaStatus;
    }

    public LocalDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDateTime createdDate) {
        this.createdDate = createdDate;
    }

    public LocalDateTime getAssignedDate() {
        return assignedDate;
    }

    public void setAssignedDate(LocalDateTime assignedDate) {
        this.assignedDate = assignedDate;
    }

    public LocalDateTime getSlaResponseDeadline() {
        return slaResponseDeadline;
    }

    public void setSlaResponseDeadline(LocalDateTime slaResponseDeadline) {
        this.slaResponseDeadline = slaResponseDeadline;
    }

    public LocalDateTime getSlaDeadline() {
        return slaDeadline;
    }

    public void setSlaDeadline(LocalDateTime slaDeadline) {
        this.slaDeadline = slaDeadline;
    }

    public LocalDateTime getFirstResponseDate() {
        return firstResponseDate;
    }

    public void setFirstResponseDate(LocalDateTime firstResponseDate) {
        this.firstResponseDate = firstResponseDate;
    }

    public LocalDateTime getResolutionDate() {
        return resolutionDate;
    }

    public void setResolutionDate(LocalDateTime resolutionDate) {
        this.resolutionDate = resolutionDate;
    }

    public LocalDateTime getClosedDate() {
        return closedDate;
    }

    public void setClosedDate(LocalDateTime closedDate) {
        this.closedDate = closedDate;
    }

    public String getRootCause() {
        return rootCause;
    }

    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public ResolutionCode getResolutionCode() {
        return resolutionCode;
    }

    public void setResolutionCode(ResolutionCode resolutionCode) {
        this.resolutionCode = resolutionCode;
    }

    public boolean isAutoCreated() {
        return autoCreated;
    }

    public void setAutoCreated(boolean autoCreated) {
        this.autoCreated = autoCreated;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    /* ---------- Derived state ---------- */

    /**
     * Still counts towards an engineer's workload and the open queue.
     */
    public boolean isActive() {
        return status != null && status.isActive();
    }

    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    public boolean isAssigned() {
        return assignedEngineerId != null;
    }

    /**
     * The engineer currently holding the ticket, if any.
     */
    public Optional<Long> findAssignedEngineerId() {
        return Optional.ofNullable(assignedEngineerId);
    }

    /**
     * Minutes left before the resolution deadline. Negative once breached,
     * empty when no deadline has been set.
     */
    public Optional<Long> getMinutesRemaining() {
        if (slaDeadline == null) {
            return Optional.empty();
        }
        return Optional.of(ChronoUnit.MINUTES.between(LocalDateTime.now(), slaDeadline));
    }

    /**
     * True once the resolution deadline has passed without a resolution.
     */
    public boolean isPastDeadline() {
        if (slaDeadline == null) {
            return false;
        }
        LocalDateTime comparedAgainst = resolutionDate == null ? LocalDateTime.now() : resolutionDate;
        return comparedAgainst.isAfter(slaDeadline);
    }

    /**
     * How much of the resolution window has been used, as a fraction. Above
     * 1.0 means the deadline has passed.
     */
    public double getConsumedFraction() {
        if (createdDate == null || slaDeadline == null) {
            return 0.0d;
        }
        long total = ChronoUnit.MINUTES.between(createdDate, slaDeadline);
        if (total <= 0) {
            return 1.0d;
        }
        LocalDateTime upTo = resolutionDate == null ? LocalDateTime.now() : resolutionDate;
        long elapsed = ChronoUnit.MINUTES.between(createdDate, upTo);
        return (double) elapsed / (double) total;
    }

    /**
     * End to end resolution time in hours, empty while still open.
     */
    public Optional<Double> getResolutionHours() {
        if (createdDate == null || resolutionDate == null) {
            return Optional.empty();
        }
        long minutes = ChronoUnit.MINUTES.between(createdDate, resolutionDate);
        return Optional.of(Math.round(minutes / 0.6d) / 100.0d);
    }

    /**
     * Whether this ticket may still climb the escalation ladder.
     */
    public boolean canEscalate() {
        return escalationLevel != null && !escalationLevel.isTopLevel() && !isTerminal();
    }

    /* ---------- Ordering ---------- */

    /**
     * Most urgent first: highest priority wins, and among equals the ticket
     * that has been waiting longest goes first. This is the ordering the
     * escalation {@link java.util.PriorityQueue} uses.
     */
    public static Comparator<TroubleTicket> byUrgency() {
        Comparator<TroubleTicket> byPriority = Comparator.comparingInt(
                ticket -> ticket.getPriority() == null ? 0 : ticket.getPriority().getWeight());
        Comparator<TroubleTicket> byAge = Comparator.comparing(
                TroubleTicket::getCreatedDate,
                Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()));
        return byPriority.reversed().thenComparing(byAge);
    }

    /**
     * Soonest deadline first, with tickets that have no deadline last.
     */
    public static Comparator<TroubleTicket> byDeadline() {
        return Comparator.comparing(TroubleTicket::getSlaDeadline,
                Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()));
    }

    /* ---------- Rendering ---------- */

    @Override
    public String getAuditEntityType() {
        return "TROUBLE_TICKET";
    }

    @Override
    public String getAuditEntityId() {
        return ticketNumber;
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-16s %-18s %-10s %-16s %-14s",
                Displayable.orDash(ticketNumber),
                category == null ? "-" : category.getDisplayName(),
                priority == null ? "-" : priority.getDisplayName(),
                status == null ? "-" : status.getDisplayName(),
                Displayable.formatDateTime(slaDeadline));
    }

    @Override
    public String toDetailBlock() {
        StringBuilder builder = new StringBuilder();
        builder.append(Displayable.labelled("Ticket Number", ticketNumber)).append(System.lineSeparator());
        builder.append(Displayable.labelled("Category",
                category == null ? null : category.getDisplayName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Description", description)).append(System.lineSeparator());
        builder.append(Displayable.labelled("Priority",
                priority == null ? null : priority.getDisplayName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Severity",
                severity == null ? null : severity.getDisplayName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Status",
                status == null ? null : status.getDisplayName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Escalation",
                escalationLevel == null ? null : escalationLevel.getDisplayName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Raised", Displayable.formatDateTime(createdDate)))
                .append(System.lineSeparator());
        builder.append(Displayable.labelled("SLA Deadline", Displayable.formatDateTime(slaDeadline)))
                .append(System.lineSeparator());
        builder.append(Displayable.labelled("SLA Status",
                slaStatus == null ? null : slaStatus.getDisplayName()));

        if (resolutionDate != null) {
            builder.append(System.lineSeparator());
            builder.append(Displayable.labelled("Resolved", Displayable.formatDateTime(resolutionDate)))
                    .append(System.lineSeparator());
            builder.append(Displayable.labelled("Resolution Code",
                    resolutionCode == null ? null : resolutionCode.getDisplayName())).append(System.lineSeparator());
            builder.append(Displayable.labelled("Root Cause", rootCause)).append(System.lineSeparator());
            builder.append(Displayable.labelled("Resolution", resolution));
        }
        return builder.toString();
    }
}
