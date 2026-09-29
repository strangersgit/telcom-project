package com.amdocs.telecom.dto;

import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.util.AppConstants;

/**
 * The headline figures behind the network manager dashboard in section 15,
 * matching the single row of {@code vw_manager_dashboard}.
 */
public class DashboardStatsDTO implements Displayable {

    private int totalOpenTickets;
    private int criticalIncidents;
    private int slaAtRisk;
    private int slaBreached;
    private int resolvedToday;
    private Double avgResolutionHours;
    private int totalTickets;

    public int getTotalOpenTickets() {
        return totalOpenTickets;
    }

    public void setTotalOpenTickets(int totalOpenTickets) {
        this.totalOpenTickets = totalOpenTickets;
    }

    public int getCriticalIncidents() {
        return criticalIncidents;
    }

    public void setCriticalIncidents(int criticalIncidents) {
        this.criticalIncidents = criticalIncidents;
    }

    public int getSlaAtRisk() {
        return slaAtRisk;
    }

    public void setSlaAtRisk(int slaAtRisk) {
        this.slaAtRisk = slaAtRisk;
    }

    /**
     * Counts every ticket that missed its deadline, including ones since
     * closed, because a breach that has been cleaned up still happened.
     */
    public int getSlaBreached() {
        return slaBreached;
    }

    public void setSlaBreached(int slaBreached) {
        this.slaBreached = slaBreached;
    }

    public int getResolvedToday() {
        return resolvedToday;
    }

    public void setResolvedToday(int resolvedToday) {
        this.resolvedToday = resolvedToday;
    }

    public Double getAvgResolutionHours() {
        return avgResolutionHours;
    }

    public void setAvgResolutionHours(Double avgResolutionHours) {
        this.avgResolutionHours = avgResolutionHours;
    }

    public int getTotalTickets() {
        return totalTickets;
    }

    public void setTotalTickets(int totalTickets) {
        this.totalTickets = totalTickets;
    }

    /**
     * Share of all tickets that ever breached their deadline.
     */
    public double getBreachPercent() {
        if (totalTickets <= 0) {
            return 0.0d;
        }
        return (slaBreached * 100.0d) / totalTickets;
    }

    @Override
    public String toSummaryLine() {
        return String.format("open %d | critical %d | at risk %d | breached %d | resolved today %d",
                totalOpenTickets, criticalIncidents, slaAtRisk, slaBreached, resolvedToday);
    }

    @Override
    public String toDetailBlock() {
        String newLine = System.lineSeparator();
        StringBuilder builder = new StringBuilder();
        builder.append(AppConstants.LINE_SINGLE).append(newLine);
        builder.append(Displayable.labelled("Open tickets", totalOpenTickets)).append(newLine);
        builder.append(Displayable.labelled("Critical", criticalIncidents)).append(newLine);
        builder.append(Displayable.labelled("SLA at risk", slaAtRisk)).append(newLine);
        builder.append(Displayable.labelled("SLA breached",
                slaBreached + String.format(" (%.1f%% of all tickets)", getBreachPercent()))).append(newLine);
        builder.append(Displayable.labelled("Resolved today", resolvedToday)).append(newLine);
        builder.append(Displayable.labelled("Avg resolution",
                avgResolutionHours == null ? null : String.format("%.2f hours", avgResolutionHours))).append(newLine);
        builder.append(Displayable.labelled("Total tickets", totalTickets)).append(newLine);
        builder.append(AppConstants.LINE_SINGLE);
        return builder.toString();
    }
}
