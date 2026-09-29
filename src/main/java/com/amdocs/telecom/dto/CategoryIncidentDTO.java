package com.amdocs.telecom.dto;

import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.enums.IncidentCategory;

/**
 * Incident volume for one category, matching {@code vw_category_incidents}.
 */
public class CategoryIncidentDTO implements Displayable {

    private IncidentCategory category;
    private int ticketCount;
    private int criticalCount;
    private int autoCreatedCount;
    private Double avgResolutionHours;
    private Double pctOfTotal;

    public IncidentCategory getCategory() {
        return category;
    }

    public void setCategory(IncidentCategory category) {
        this.category = category;
    }

    public int getTicketCount() {
        return ticketCount;
    }

    public void setTicketCount(int ticketCount) {
        this.ticketCount = ticketCount;
    }

    public int getCriticalCount() {
        return criticalCount;
    }

    public void setCriticalCount(int criticalCount) {
        this.criticalCount = criticalCount;
    }

    /**
     * How many of these were opened by the event processor rather than by a
     * person.
     */
    public int getAutoCreatedCount() {
        return autoCreatedCount;
    }

    public void setAutoCreatedCount(int autoCreatedCount) {
        this.autoCreatedCount = autoCreatedCount;
    }

    public Double getAvgResolutionHours() {
        return avgResolutionHours;
    }

    public void setAvgResolutionHours(Double avgResolutionHours) {
        this.avgResolutionHours = avgResolutionHours;
    }

    public Double getPctOfTotal() {
        return pctOfTotal;
    }

    public void setPctOfTotal(Double pctOfTotal) {
        this.pctOfTotal = pctOfTotal;
    }

    /**
     * Proportional bar for the console, one block per whole five per cent.
     */
    public String toBar() {
        int blocks = pctOfTotal == null ? 0 : (int) Math.round(pctOfTotal / 5.0d);
        StringBuilder bar = new StringBuilder(20);
        for (int index = 0; index < blocks; index++) {
            bar.append('#');
        }
        return bar.toString();
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-22s %6d %9d %10d %8s %7s  %s",
                category == null ? "-" : category.getDisplayName(),
                ticketCount,
                criticalCount,
                autoCreatedCount,
                avgResolutionHours == null ? "n/a" : String.format("%.2f h", avgResolutionHours),
                pctOfTotal == null ? "n/a" : String.format("%.1f%%", pctOfTotal),
                toBar());
    }
}
