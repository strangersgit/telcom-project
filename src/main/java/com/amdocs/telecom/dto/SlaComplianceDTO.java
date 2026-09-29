package com.amdocs.telecom.dto;

import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.enums.Priority;

/**
 * SLA performance for one priority band, matching {@code vw_sla_compliance}.
 *
 * <p>Compliance is measured against tickets that have actually finished. A
 * ticket still in flight has neither met nor missed its deadline, so counting
 * it either way would distort the figure.</p>
 */
public class SlaComplianceDTO implements Displayable {

    private Priority priority;
    private int responseMinutes;
    private int resolutionMinutes;
    private int totalTickets;
    private int completedTickets;
    private int metSla;
    private int breachedSla;
    private Double compliancePercent;
    private Double avgResolutionHours;

    public Priority getPriority() {
        return priority;
    }

    public void setPriority(Priority priority) {
        this.priority = priority;
    }

    public int getResponseMinutes() {
        return responseMinutes;
    }

    public void setResponseMinutes(int responseMinutes) {
        this.responseMinutes = responseMinutes;
    }

    public int getResolutionMinutes() {
        return resolutionMinutes;
    }

    public void setResolutionMinutes(int resolutionMinutes) {
        this.resolutionMinutes = resolutionMinutes;
    }

    public int getTotalTickets() {
        return totalTickets;
    }

    public void setTotalTickets(int totalTickets) {
        this.totalTickets = totalTickets;
    }

    public int getCompletedTickets() {
        return completedTickets;
    }

    public void setCompletedTickets(int completedTickets) {
        this.completedTickets = completedTickets;
    }

    public int getMetSla() {
        return metSla;
    }

    public void setMetSla(int metSla) {
        this.metSla = metSla;
    }

    public int getBreachedSla() {
        return breachedSla;
    }

    public void setBreachedSla(int breachedSla) {
        this.breachedSla = breachedSla;
    }

    /**
     * Null when nothing in this band has been resolved yet, because the view
     * guards the division.
     */
    public Double getCompliancePercent() {
        return compliancePercent;
    }

    public void setCompliancePercent(Double compliancePercent) {
        this.compliancePercent = compliancePercent;
    }

    public Double getAvgResolutionHours() {
        return avgResolutionHours;
    }

    public void setAvgResolutionHours(Double avgResolutionHours) {
        this.avgResolutionHours = avgResolutionHours;
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-10s %6d %8d %6d %9d %10s %10s",
                priority == null ? "-" : priority.getDisplayName(),
                totalTickets,
                completedTickets,
                metSla,
                breachedSla,
                compliancePercent == null ? "n/a" : String.format("%.2f%%", compliancePercent),
                avgResolutionHours == null ? "n/a" : String.format("%.2f h", avgResolutionHours));
    }
}
