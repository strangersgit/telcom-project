package com.amdocs.telecom.dto;

import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.model.enums.Specialization;

import java.util.Comparator;

/**
 * A candidate engineer for an unassigned ticket, matching the result set of
 * {@code sp_recommend_engineers}.
 *
 * <p>The same shape is produced by the Java recommendation path in section 9,
 * so the console shows one list whichever route produced it.</p>
 */
public class EngineerRecommendationDTO implements Displayable {

    private Long engineerId;
    private String employeeCode;
    private String engineerName;
    private Specialization specialization;
    private Region region;
    private int experienceYears;
    private int activeTicketCount;
    private int maxTicketCapacity;
    private int spareCapacity;

    public EngineerRecommendationDTO() {
        // Populated field by field from a result set or from the domain object.
    }

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

    public int getSpareCapacity() {
        return spareCapacity;
    }

    public void setSpareCapacity(int spareCapacity) {
        this.spareCapacity = spareCapacity;
    }

    /**
     * Lightest workload first, most experienced first among equals. Matches
     * the {@code ORDER BY} in the stored procedure so both routes rank the
     * same candidates identically.
     */
    public static Comparator<EngineerRecommendationDTO> preferredOrder() {
        Comparator<EngineerRecommendationDTO> byWorkload =
                Comparator.comparingInt(EngineerRecommendationDTO::getActiveTicketCount);
        Comparator<EngineerRecommendationDTO> byExperience =
                Comparator.comparingInt(EngineerRecommendationDTO::getExperienceYears);
        Comparator<EngineerRecommendationDTO> byCode =
                Comparator.comparing(EngineerRecommendationDTO::getEmployeeCode);
        return byWorkload.thenComparing(byExperience.reversed()).thenComparing(byCode);
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-10s %-20s %-22s %-10s %3d yrs  %d/%d busy  %d free",
                Displayable.orDash(employeeCode),
                Displayable.truncate(engineerName, 20),
                specialization == null ? "-" : specialization.getDisplayName(),
                region == null ? "-" : region.getDisplayName(),
                experienceYears,
                activeTicketCount,
                maxTicketCapacity,
                spareCapacity);
    }
}
