package com.amdocs.telecom.service.assignment;

import com.amdocs.telecom.dto.EngineerRecommendationDTO;
import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.NetworkEngineer;

import java.util.Comparator;

/**
 * One candidate engineer, together with why the search found them.
 *
 * <p>The tier is the part a list of engineers alone cannot say. Three
 * candidates ordered by workload look interchangeable until you know the
 * first two are in another region because nobody local holds the skill,
 * which is exactly the kind of thing a service desk operator should see
 * before clicking assign.</p>
 */
public final class EngineerMatch implements Displayable {

    private final NetworkEngineer engineer;
    private final MatchTier tier;

    EngineerMatch(NetworkEngineer engineer, MatchTier tier) {
        if (engineer == null || tier == null) {
            throw new IllegalArgumentException("A match needs both an engineer and a tier");
        }
        this.engineer = engineer;
        this.tier = tier;
    }

    public NetworkEngineer getEngineer() {
        return engineer;
    }

    public MatchTier getTier() {
        return tier;
    }

    public Long getEngineerId() {
        return engineer.getId();
    }

    public String getEmployeeCode() {
        return engineer.getEmployeeCode();
    }

    /**
     * Whether an automatic assignment may take this candidate.
     */
    public boolean isAutomatic() {
        return tier.isAutomatic();
    }

    /**
     * Candidates ranked as section 7 asks: closest fit first, then the
     * lightest workload, then the most experience.
     *
     * <p>The tie-break on employee code at the end of
     * {@link NetworkEngineer#byWorkloadThenExperience()} is what makes the
     * order total. Without it two equally loaded, equally experienced
     * engineers would come back in whatever order the database happened to
     * return them, and the same query would recommend different people on
     * different runs.</p>
     */
    public static Comparator<EngineerMatch> preferredOrder() {
        Comparator<EngineerMatch> byTier = Comparator.comparing(EngineerMatch::getTier);
        return byTier.thenComparing(EngineerMatch::getEngineer,
                NetworkEngineer.byWorkloadThenExperience());
    }

    /**
     * The shape the console renders, shared with the result of
     * {@code sp_recommend_engineers} so one list serves both routes.
     */
    public EngineerRecommendationDTO toRecommendation() {
        EngineerRecommendationDTO view = new EngineerRecommendationDTO();
        view.setEngineerId(engineer.getId());
        view.setEmployeeCode(engineer.getEmployeeCode());
        view.setEngineerName(engineer.getEngineerName());
        view.setSpecialization(engineer.getSpecialization());
        view.setRegion(engineer.getRegion());
        view.setExperienceYears(engineer.getExperienceYears());
        view.setActiveTicketCount(engineer.getActiveTicketCount());
        view.setMaxTicketCapacity(engineer.getMaxTicketCapacity());
        view.setSpareCapacity(engineer.getSpareCapacity());
        return view;
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-10s %-20s %-22s %-10s %3d yrs  %d/%d busy  %s",
                Displayable.orDash(engineer.getEmployeeCode()),
                Displayable.truncate(engineer.getEngineerName(), 20),
                engineer.getSpecialization() == null
                        ? "-" : engineer.getSpecialization().getDisplayName(),
                engineer.getRegion() == null ? "-" : engineer.getRegion().getDisplayName(),
                engineer.getExperienceYears(),
                engineer.getActiveTicketCount(),
                engineer.getMaxTicketCapacity(),
                tier.getReason());
    }

    @Override
    public String toString() {
        return engineer.getEmployeeCode() + " (" + tier.getDisplayName() + ")";
    }
}
