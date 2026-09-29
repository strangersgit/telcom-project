package com.amdocs.telecom.model;

import com.amdocs.telecom.model.enums.Priority;

import java.time.LocalDateTime;

/**
 * The response and resolution windows for one priority band, as tabulated in
 * section 8 of the case study.
 *
 * <p>Holding these in a table rather than in code means the operator can
 * retune an SLA without a rebuild, and the same numbers drive both the Java
 * SLA service and the database's stored functions.</p>
 */
public class SLAConfiguration extends TimestampedEntity {

    private static final long serialVersionUID = 1L;

    private Priority priority;
    private int responseMinutes;
    private int resolutionMinutes;
    private int atRiskThresholdPercent = 80;
    private String description;
    private boolean active = true;

    public SLAConfiguration() {
        super();
    }

    public SLAConfiguration(Priority priority, int responseMinutes, int resolutionMinutes) {
        this.priority = priority;
        this.responseMinutes = responseMinutes;
        this.resolutionMinutes = resolutionMinutes;
    }

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

    public int getAtRiskThresholdPercent() {
        return atRiskThresholdPercent;
    }

    public void setAtRiskThresholdPercent(int atRiskThresholdPercent) {
        this.atRiskThresholdPercent = atRiskThresholdPercent;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * When a first response is due for a ticket raised at the given moment.
     */
    public LocalDateTime calculateResponseDeadline(LocalDateTime createdDate) {
        return createdDate == null ? null : createdDate.plusMinutes(responseMinutes);
    }

    /**
     * When resolution is due for a ticket raised at the given moment.
     */
    public LocalDateTime calculateResolutionDeadline(LocalDateTime createdDate) {
        return createdDate == null ? null : createdDate.plusMinutes(resolutionMinutes);
    }

    /**
     * The point at which a ticket stops being comfortable and starts being
     * at risk.
     */
    public LocalDateTime calculateAtRiskThreshold(LocalDateTime createdDate) {
        if (createdDate == null) {
            return null;
        }
        long minutes = Math.round(resolutionMinutes * (atRiskThresholdPercent / 100.0d));
        return createdDate.plusMinutes(minutes);
    }

    /**
     * Human readable form of the resolution window, for example "2 hours".
     */
    public String describeResolutionWindow() {
        return describeMinutes(resolutionMinutes);
    }

    public String describeResponseWindow() {
        return describeMinutes(responseMinutes);
    }

    private static String describeMinutes(int minutes) {
        if (minutes < 60) {
            return minutes + " min";
        }
        if (minutes % 1440 == 0) {
            int days = minutes / 1440;
            return days + (days == 1 ? " day" : " days");
        }
        if (minutes % 60 == 0) {
            int hours = minutes / 60;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-10s response %-10s resolution %-10s at-risk at %d%%",
                priority == null ? "-" : priority.getDisplayName(),
                describeResponseWindow(),
                describeResolutionWindow(),
                atRiskThresholdPercent);
    }
}
