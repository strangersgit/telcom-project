package com.amdocs.telecom.dto;

import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.enums.EngineerAvailability;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.model.enums.Specialization;

/**
 * An engineer's roster entry with their lifetime workload and performance,
 * matching {@code vw_engineer_workload}.
 *
 * <p>An engineer who has never been assigned anything still appears, with
 * zeroes, because the view uses a left join. That matters for the workload
 * report: a name missing from the list would look like an oversight rather
 * than an idle engineer.</p>
 */
public class EngineerWorkloadDTO implements Displayable {

    private Long engineerId;
    private String employeeCode;
    private String engineerName;
    private Specialization specialization;
    private Region region;
    private int experienceYears;
    private EngineerAvailability availability;
    private int activeTicketCount;
    private int maxTicketCapacity;
    private int totalAssigned;
    private int totalResolved;
    private int currentlyOpen;
    private int slaBreaches;
    private Double avgResolutionHours;

    public Long getEngineerId() {
        return engineerId;
    }

    public void setEngineerId(Long engineerId) {
        this.engineerId = engineerId;
    }

    public String getEmployeeCode() {
        return employeeCode;
    }

    public void setEmployeeCode(String employeeCode) {
        this.employeeCode = employeeCode;
    }

    public String getEngineerName() {
        return engineerName;
    }

    public void setEngineerName(String engineerName) {
        this.engineerName = engineerName;
    }

    public Specialization getSpecialization() {
        return specialization;
    }

    public void setSpecialization(Specialization specialization) {
        this.specialization = specialization;
    }

    public Region getRegion() {
        return region;
    }

    public void setRegion(Region region) {
        this.region = region;
    }

    public int getExperienceYears() {
        return experienceYears;
    }

    public void setExperienceYears(int experienceYears) {
        this.experienceYears = experienceYears;
    }

    public EngineerAvailability getAvailability() {
        return availability;
    }

    public void setAvailability(EngineerAvailability availability) {
        this.availability = availability;
    }

    public int getActiveTicketCount() {
        return activeTicketCount;
    }

    public void setActiveTicketCount(int activeTicketCount) {
        this.activeTicketCount = activeTicketCount;
    }

    public int getMaxTicketCapacity() {
        return maxTicketCapacity;
    }

    public void setMaxTicketCapacity(int maxTicketCapacity) {
        this.maxTicketCapacity = maxTicketCapacity;
    }

    public int getTotalAssigned() {
        return totalAssigned;
    }

    public void setTotalAssigned(int totalAssigned) {
        this.totalAssigned = totalAssigned;
    }

    public int getTotalResolved() {
        return totalResolved;
    }

    public void setTotalResolved(int totalResolved) {
        this.totalResolved = totalResolved;
    }

    public int getCurrentlyOpen() {
        return currentlyOpen;
    }

    public void setCurrentlyOpen(int currentlyOpen) {
        this.currentlyOpen = currentlyOpen;
    }

    public int getSlaBreaches() {
        return slaBreaches;
    }

    public void setSlaBreaches(int slaBreaches) {
        this.slaBreaches = slaBreaches;
    }

    public Double getAvgResolutionHours() {
        return avgResolutionHours;
    }

    public void setAvgResolutionHours(Double avgResolutionHours) {
        this.avgResolutionHours = avgResolutionHours;
    }

    /**
     * Share of completed tickets that met their deadline. Returns 100 for an
     * engineer who has not finished anything yet, which keeps a new joiner
     * from appearing to have a zero record.
     */
    public double getSlaCompliancePercent() {
        if (totalResolved <= 0) {
            return 100.0d;
        }
        return ((totalResolved - slaBreaches) * 100.0d) / totalResolved;
    }

    public double getUtilisationPercent() {
        if (maxTicketCapacity <= 0) {
            return 0.0d;
        }
        return (activeTicketCount * 100.0d) / maxTicketCapacity;
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-10s %-20s %-22s %-10s %4d/%-3d %5d %5d %6.1f%% %8s",
                Displayable.orDash(employeeCode),
                Displayable.truncate(engineerName, 20),
                specialization == null ? "-" : specialization.getDisplayName(),
                region == null ? "-" : region.getDisplayName(),
                activeTicketCount,
                maxTicketCapacity,
                totalResolved,
                slaBreaches,
                getSlaCompliancePercent(),
                avgResolutionHours == null ? "-" : String.format("%.2f h", avgResolutionHours));
    }
}
