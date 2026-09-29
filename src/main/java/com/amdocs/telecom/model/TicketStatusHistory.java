package com.amdocs.telecom.model;

import com.amdocs.telecom.model.enums.TicketStatus;

import java.time.LocalDateTime;

/**
 * One entry in a ticket's status trail, written by the service layer inside
 * the same transaction as the status change it records.
 *
 * <p>Append only, so there is nothing to update and no {@code updated_at}.</p>
 */
public class TicketStatusHistory extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long ticketId;
    private TicketStatus oldStatus;
    private TicketStatus newStatus;
    private String changedBy;
    private LocalDateTime changedDate;
    private String remarks;

    public TicketStatusHistory() {
        super();
    }

    public TicketStatusHistory(Long ticketId, TicketStatus oldStatus, TicketStatus newStatus,
                               String changedBy, String remarks) {
        this.ticketId = ticketId;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.changedBy = changedBy;
        this.remarks = remarks;
        this.changedDate = stampNow();
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    /**
     * Null for the very first entry, where the ticket was created rather than
     * moved from somewhere.
     */
    public TicketStatus getOldStatus() {
        return oldStatus;
    }

    public void setOldStatus(TicketStatus oldStatus) {
        this.oldStatus = oldStatus;
    }

    public TicketStatus getNewStatus() {
        return newStatus;
    }

    public void setNewStatus(TicketStatus newStatus) {
        this.newStatus = newStatus;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(String changedBy) {
        this.changedBy = changedBy;
    }

    public LocalDateTime getChangedDate() {
        return changedDate;
    }

    public void setChangedDate(LocalDateTime changedDate) {
        this.changedDate = changedDate;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public boolean isCreationEntry() {
        return oldStatus == null;
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-18s %-16s -> %-16s %-14s %s",
                Displayable.formatDateTime(changedDate),
                oldStatus == null ? "(new)" : oldStatus.getDisplayName(),
                newStatus == null ? "-" : newStatus.getDisplayName(),
                Displayable.truncate(changedBy, 14),
                Displayable.truncate(remarks, 40));
    }
}
