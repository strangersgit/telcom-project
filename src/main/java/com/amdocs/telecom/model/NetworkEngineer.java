package com.amdocs.telecom.model;

import com.amdocs.telecom.model.enums.EngineerAvailability;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.model.enums.Role;
import com.amdocs.telecom.model.enums.Specialization;

import java.util.Comparator;

/**
 * A field or network operations engineer, as tabulated in section 7 of the
 * case study.
 *
 * <p>{@code activeTicketCount} is a maintained aggregate rather than a live
 * count, kept in step by the assignment transaction. That keeps the
 * recommendation query a single indexed read instead of a join and count over
 * every open ticket.</p>
 */
public class NetworkEngineer extends AbstractParty {

    private static final long serialVersionUID = 1L;

    private String employeeCode;
    private String mobileNumber;
    private Specialization specialization;
    private Region region;
    private int experienceYears;
    private EngineerAvailability availability = EngineerAvailability.AVAILABLE;
    private int activeTicketCount;
    private int maxTicketCapacity = 10;
    private Long userId;

    public NetworkEngineer() {
        super();
    }

    public NetworkEngineer(String employeeCode, String engineerName, String email,
                           Specialization specialization, Region region, int experienceYears) {
        super(null, engineerName, email);
        this.employeeCode = employeeCode;
        this.specialization = specialization;
        this.region = region;
        this.experienceYears = experienceYears;
    }

    public String getEmployeeCode() {
        return employeeCode;
    }

    public void setEmployeeCode(String employeeCode) {
        this.employeeCode = employeeCode;
    }

    /**
     * Alias for the inherited name, matching the case study's field naming.
     */
    public String getEngineerName() {
        return getName();
    }

    public void setEngineerName(String engineerName) {
        setName(engineerName);
    }

    public String getMobileNumber() {
        return mobileNumber;
    }

    public void setMobileNumber(String mobileNumber) {
        this.mobileNumber = mobileNumber;
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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /**
     * Whether this engineer can take another ticket right now: on duty, and
     * not already at capacity.
     */
    public boolean canAcceptWork() {
        return availability != null
                && availability.canAcceptWork()
                && activeTicketCount < maxTicketCapacity;
    }

    public int getSpareCapacity() {
        return Math.max(0, maxTicketCapacity - activeTicketCount);
    }

    public double getUtilisationPercent() {
        if (maxTicketCapacity <= 0) {
            return 0.0d;
        }
        return (activeTicketCount * 100.0d) / maxTicketCapacity;
    }

    /**
     * Whether this engineer's skill matches what an incident needs.
     */
    public boolean hasSpecialization(Specialization required) {
        return specialization == required;
    }

    public boolean servesRegion(Region required) {
        return required == null || region == required;
    }

    /**
     * The ordering the case study describes for recommendations: lightest
     * workload first, and where two engineers are equally loaded, the more
     * experienced one wins.
     */
    public static Comparator<NetworkEngineer> byWorkloadThenExperience() {
        Comparator<NetworkEngineer> byWorkload =
                Comparator.comparingInt(NetworkEngineer::getActiveTicketCount);
        Comparator<NetworkEngineer> byExperience =
                Comparator.comparingInt(NetworkEngineer::getExperienceYears);
        Comparator<NetworkEngineer> byCode =
                Comparator.comparing(NetworkEngineer::getEmployeeCode);
        return byWorkload.thenComparing(byExperience.reversed()).thenComparing(byCode);
    }

    @Override
    public Role getRole() {
        return Role.NETWORK_ENGINEER;
    }

    @Override
    public String getBusinessKey() {
        return employeeCode;
    }

    @Override
    public String getAuditEntityType() {
        return "NETWORK_ENGINEER";
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-10s %-20s %-22s %-10s %5d yrs %4d active  %-12s",
                Displayable.orDash(employeeCode),
                Displayable.truncate(getName(), 20),
                specialization == null ? "-" : specialization.getDisplayName(),
                region == null ? "-" : region.getDisplayName(),
                experienceYears,
                activeTicketCount,
                availability == null ? "-" : availability.getDisplayName());
    }

    @Override
    public String toDetailBlock() {
        StringBuilder builder = new StringBuilder();
        builder.append(Displayable.labelled("Employee Code", employeeCode)).append(System.lineSeparator());
        builder.append(Displayable.labelled("Name", getName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Specialization",
                specialization == null ? null : specialization.getDisplayName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Region",
                region == null ? null : region.getDisplayName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Experience", experienceYears + " years")).append(System.lineSeparator());
        builder.append(Displayable.labelled("Availability",
                availability == null ? null : availability.getDisplayName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Workload",
                activeTicketCount + " of " + maxTicketCapacity
                        + String.format(" (%.0f%%)", getUtilisationPercent()))).append(System.lineSeparator());
        builder.append(Displayable.labelled("Email", getEmail())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Mobile", mobileNumber));
        return builder.toString();
    }
}
