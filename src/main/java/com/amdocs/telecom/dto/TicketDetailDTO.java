package com.amdocs.telecom.dto;

import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.enums.CustomerType;
import com.amdocs.telecom.model.enums.EscalationLevel;
import com.amdocs.telecom.model.enums.IncidentCategory;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.model.enums.ResolutionCode;
import com.amdocs.telecom.model.enums.SLAStatus;
import com.amdocs.telecom.model.enums.ServiceType;
import com.amdocs.telecom.model.enums.Severity;
import com.amdocs.telecom.model.enums.Specialization;
import com.amdocs.telecom.model.enums.TicketStatus;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * A ticket with its customer, service and engineer already resolved,
 * matching the {@code vw_ticket_details} view.
 *
 * <p>Read only by design: the view computes the live SLA status and the
 * minutes remaining in the database, so a screen showing a ticket needs one
 * query rather than four plus a calculation.</p>
 */
public class TicketDetailDTO implements Displayable {

    private Long ticketId;
    private String ticketNumber;

    private String customerNumber;
    private String customerName;
    private CustomerType customerType;
    private String city;
    private String customerRegion;

    private String serviceCode;
    private String serviceName;
    private ServiceType serviceType;

    private IncidentCategory category;
    private String description;
    private Priority priority;
    private Severity severity;
    private TicketStatus status;
    private EscalationLevel escalationLevel;

    private String engineerCode;
    private String engineerName;
    private Specialization engineerSpecialization;
    private String engineerRegion;

    private LocalDateTime createdDate;
    private LocalDateTime assignedDate;
    private LocalDateTime slaResponseDeadline;
    private LocalDateTime slaDeadline;
    private LocalDateTime resolutionDate;
    private LocalDateTime closedDate;

    private String rootCause;
    private String resolution;
    private ResolutionCode resolutionCode;
    private boolean autoCreated;

    private SLAStatus liveSlaStatus;
    private Long minutesRemaining;
    private Double resolutionHours;

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public String getTicketNumber() {
        return ticketNumber;
    }

    public void setTicketNumber(String ticketNumber) {
        this.ticketNumber = ticketNumber;
    }

    public String getCustomerNumber() {
        return customerNumber;
    }

    public void setCustomerNumber(String customerNumber) {
        this.customerNumber = customerNumber;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public CustomerType getCustomerType() {
        return customerType;
    }

    public void setCustomerType(CustomerType customerType) {
        this.customerType = customerType;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getCustomerRegion() {
        return customerRegion;
    }

    public void setCustomerRegion(String customerRegion) {
        this.customerRegion = customerRegion;
    }

    public String getServiceCode() {
        return serviceCode;
    }

    public void setServiceCode(String serviceCode) {
        this.serviceCode = serviceCode;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public ServiceType getServiceType() {
        return serviceType;
    }

    public void setServiceType(ServiceType serviceType) {
        this.serviceType = serviceType;
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

    public EscalationLevel getEscalationLevel() {
        return escalationLevel;
    }

    public void setEscalationLevel(EscalationLevel escalationLevel) {
        this.escalationLevel = escalationLevel;
    }

    public String getEngineerCode() {
        return engineerCode;
    }

    public void setEngineerCode(String engineerCode) {
        this.engineerCode = engineerCode;
    }

    public String getEngineerName() {
        return engineerName;
    }

    public void setEngineerName(String engineerName) {
        this.engineerName = engineerName;
    }

    public Specialization getEngineerSpecialization() {
        return engineerSpecialization;
    }

    public void setEngineerSpecialization(Specialization engineerSpecialization) {
        this.engineerSpecialization = engineerSpecialization;
    }

    public String getEngineerRegion() {
        return engineerRegion;
    }

    public void setEngineerRegion(String engineerRegion) {
        this.engineerRegion = engineerRegion;
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

    public SLAStatus getLiveSlaStatus() {
        return liveSlaStatus;
    }

    public void setLiveSlaStatus(SLAStatus liveSlaStatus) {
        this.liveSlaStatus = liveSlaStatus;
    }

    public Long getMinutesRemaining() {
        return minutesRemaining;
    }

    public void setMinutesRemaining(Long minutesRemaining) {
        this.minutesRemaining = minutesRemaining;
    }

    public Double getResolutionHours() {
        return resolutionHours;
    }

    public void setResolutionHours(Double resolutionHours) {
        this.resolutionHours = resolutionHours;
    }

    public boolean isAssigned() {
        return engineerCode != null;
    }

    public Optional<String> findEngineerCode() {
        return Optional.ofNullable(engineerCode);
    }

    /**
     * Time left rendered for a console, for example "1h 45m left" or
     * "2h 10m overdue".
     */
    public String describeTimeRemaining() {
        if (minutesRemaining == null) {
            return "-";
        }
        long minutes = Math.abs(minutesRemaining);
        String span = (minutes / 60) + "h " + (minutes % 60) + "m";
        return minutesRemaining < 0 ? span + " overdue" : span + " left";
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-16s %-12s %-18s %-10s %-16s %-12s %s",
                Displayable.orDash(ticketNumber),
                Displayable.orDash(customerNumber),
                category == null ? "-" : category.getDisplayName(),
                priority == null ? "-" : priority.getDisplayName(),
                status == null ? "-" : status.getDisplayName(),
                Displayable.orDash(engineerCode),
                liveSlaStatus == null ? "-" : liveSlaStatus.getDisplayName());
    }

    /**
     * The ticket card laid out as section 5 of the case study shows it.
     */
    @Override
    public String toDetailBlock() {
        String newLine = System.lineSeparator();
        StringBuilder builder = new StringBuilder();
        builder.append(Displayable.labelled("Ticket Number", ticketNumber)).append(newLine);
        builder.append(Displayable.labelled("Customer",
                Displayable.orDash(customerNumber) + " - " + Displayable.orDash(customerName))).append(newLine);
        builder.append(Displayable.labelled("Service", serviceName)).append(newLine);
        builder.append(Displayable.labelled("Category",
                category == null ? null : category.name())).append(newLine);
        builder.append(Displayable.labelled("Description", description)).append(newLine);
        builder.append(Displayable.labelled("Priority",
                priority == null ? null : priority.name())).append(newLine);
        builder.append(Displayable.labelled("Severity",
                severity == null ? null : severity.name())).append(newLine);
        builder.append(Displayable.labelled("Status",
                status == null ? null : status.name())).append(newLine);
        builder.append(Displayable.labelled("Engineer",
                engineerCode == null ? "UNASSIGNED"
                        : engineerCode + " - " + Displayable.orDash(engineerName))).append(newLine);
        builder.append(Displayable.labelled("Escalation",
                escalationLevel == null ? null : escalationLevel.name())).append(newLine);
        builder.append(Displayable.labelled("Raised",
                Displayable.formatDateTime(createdDate))).append(newLine);
        builder.append(Displayable.labelled("SLA Deadline",
                Displayable.formatDateTime(slaDeadline))).append(newLine);
        builder.append(Displayable.labelled("SLA Status",
                (liveSlaStatus == null ? "-" : liveSlaStatus.name()) + " (" + describeTimeRemaining() + ")"));

        if (resolutionDate != null) {
            builder.append(newLine);
            builder.append(Displayable.labelled("Resolved",
                    Displayable.formatDateTime(resolutionDate))).append(newLine);
            builder.append(Displayable.labelled("Took",
                    resolutionHours == null ? null : resolutionHours + " hours")).append(newLine);
            builder.append(Displayable.labelled("Resolution Code",
                    resolutionCode == null ? null : resolutionCode.name())).append(newLine);
            builder.append(Displayable.labelled("Root Cause", rootCause)).append(newLine);
            builder.append(Displayable.labelled("Resolution", resolution));
        }
        return builder.toString();
    }
}
