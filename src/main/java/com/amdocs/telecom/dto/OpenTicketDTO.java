package com.amdocs.telecom.dto;

import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.enums.EscalationLevel;
import com.amdocs.telecom.model.enums.IncidentCategory;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.model.enums.SLAStatus;
import com.amdocs.telecom.model.enums.Severity;
import com.amdocs.telecom.model.enums.TicketStatus;

import java.time.LocalDateTime;
import java.util.Comparator;

/**
 * A row of the service desk queue, matching {@code vw_open_tickets}.
 *
 * <p>Carries the priority weight the view computes so the console can sort
 * without reaching back into the {@link Priority} enum, and so a Stream
 * sorted in Java agrees with a query sorted in SQL.</p>
 */
public class OpenTicketDTO implements Displayable {

    private Long ticketId;
    private String ticketNumber;
    private String customerNumber;
    private String customerName;
    private String serviceName;
    private IncidentCategory category;
    private Priority priority;
    private Severity severity;
    private TicketStatus status;
    private EscalationLevel escalationLevel;
    private String engineerCode;
    private LocalDateTime createdDate;
    private LocalDateTime slaDeadline;
    private SLAStatus slaStatus;
    private Long minutesRemaining;
    private int priorityWeight;

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

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public IncidentCategory getCategory() {
        return category;
    }

    public void setCategory(IncidentCategory category) {
        this.category = category;
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

    /**
     * Reads {@code UNASSIGNED} rather than null when no engineer holds the
     * ticket, because the view substitutes that value.
     */
    public String getEngineerCode() {
        return engineerCode;
    }

    public void setEngineerCode(String engineerCode) {
        this.engineerCode = engineerCode;
    }

    public LocalDateTime getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(LocalDateTime createdDate) {
        this.createdDate = createdDate;
    }

    public LocalDateTime getSlaDeadline() {
        return slaDeadline;
    }

    public void setSlaDeadline(LocalDateTime slaDeadline) {
        this.slaDeadline = slaDeadline;
    }

    public SLAStatus getSlaStatus() {
        return slaStatus;
    }

    public void setSlaStatus(SLAStatus slaStatus) {
        this.slaStatus = slaStatus;
    }

    public Long getMinutesRemaining() {
        return minutesRemaining;
    }

    public void setMinutesRemaining(Long minutesRemaining) {
        this.minutesRemaining = minutesRemaining;
    }

    public int getPriorityWeight() {
        return priorityWeight;
    }

    public void setPriorityWeight(int priorityWeight) {
        this.priorityWeight = priorityWeight;
    }

    public boolean isUnassigned() {
        return engineerCode == null || "UNASSIGNED".equals(engineerCode);
    }

    public boolean isBreached() {
        return slaStatus == SLAStatus.BREACHED;
    }

    /**
     * Queue order: most urgent first, oldest first among equals.
     */
    public static Comparator<OpenTicketDTO> byUrgency() {
        Comparator<OpenTicketDTO> byWeight =
                Comparator.comparingInt(OpenTicketDTO::getPriorityWeight);
        Comparator<OpenTicketDTO> byAge = Comparator.comparing(
                OpenTicketDTO::getCreatedDate,
                Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()));
        return byWeight.reversed().thenComparing(byAge);
    }

    /**
     * Deadline order, which is what the SLA monitor works through.
     */
    public static Comparator<OpenTicketDTO> byDeadline() {
        return Comparator.comparing(OpenTicketDTO::getSlaDeadline,
                Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()));
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-16s %-12s %-16s %-10s %-16s %-12s %-18s %s",
                Displayable.orDash(ticketNumber),
                Displayable.orDash(customerNumber),
                category == null ? "-" : category.getDisplayName(),
                priority == null ? "-" : priority.getDisplayName(),
                status == null ? "-" : status.getDisplayName(),
                Displayable.orDash(engineerCode),
                Displayable.formatDateTime(slaDeadline),
                slaStatus == null ? "-" : slaStatus.getDisplayName());
    }
}
