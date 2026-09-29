package com.amdocs.telecom.service.assignment;

import com.amdocs.telecom.model.NetworkEngineer;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.model.enums.Specialization;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Picks the engineers best suited to a ticket, from a roster handed to it.
 *
 * <p>This class does no database work at all. It is given a list of
 * engineers and returns a ranked list of candidates, which is what makes
 * section 7's five criteria testable without a schema and keeps the ranking
 * rules in one readable place rather than spread across a WHERE clause, an
 * ORDER BY and a stored procedure.</p>
 *
 * <h3>The five criteria of section 7</h3>
 *
 * <p>They do not all work the same way, and the distinction matters:</p>
 *
 * <ul>
 *   <li><b>Availability</b> and <b>workload</b> are gates. An engineer who
 *       is on leave or already at capacity is excluded, never merely ranked
 *       lower, because handing them the ticket would not get it fixed.
 *   <li><b>Specialization</b> and <b>region</b> are preferences. They decide
 *       which {@link MatchTier} a candidate lands in, so the search can
 *       widen when nobody satisfies both rather than reporting that nobody
 *       can help.
 *   <li><b>Experience</b> is a tie-break. Among equally loaded engineers the
 *       more experienced one is offered first.
 * </ul>
 *
 * <p>Java 8 does the work throughout, as section 7 requires: a
 * {@link Stream} per tier, {@link Predicate} lambdas for the gates, a
 * {@link Comparator} chain for the ranking, and {@link Optional} for the
 * single best candidate, since "nobody suitable" is an ordinary answer here
 * rather than an error.</p>
 */
public final class EngineerRecommender {

    /**
     * How many candidates section 16's worked example asks for: "the three
     * engineers with the lowest active workload who have the required
     * specialization and are currently available".
     */
    public static final int DEFAULT_SHORTLIST = 3;

    /**
     * An upper bound so a caller cannot ask for the whole roster as a
     * shortlist.
     */
    public static final int MAX_SHORTLIST = 25;

    /**
     * Engineers who could take a ticket now. The two gates of section 7,
     * as a lambda.
     */
    private static final Predicate<NetworkEngineer> CAN_TAKE_WORK = NetworkEngineer::canAcceptWork;

    /**
     * Ranks every engineer who could take the ticket, closest fit first.
     *
     * <p>Candidates are placed in tiers rather than filtered to one: the
     * result holds the local specialists, then the remote specialists, then
     * the local non-specialists, each group internally ordered by workload
     * and experience. A caller wanting only what may be assigned
     * automatically filters on {@link EngineerMatch#isAutomatic()}.</p>
     *
     * @param roster         engineers to consider, typically the whole team
     * @param required       the skill the incident category calls for
     * @param region         the ticket's region, or null to ignore region
     *                       entirely
     * @return the ranked candidates, possibly empty, never null
     */
    public List<EngineerMatch> rank(List<NetworkEngineer> roster, Specialization required,
                                    Region region) {
        if (roster == null || roster.isEmpty()) {
            return Collections.emptyList();
        }
        if (required == null) {
            throw new IllegalArgumentException("A required specialization is needed to rank");
        }
        // A ticket with no region to compare against cannot distinguish the
        // first two tiers, so every specialist is an exact match and the
        // third tier would be every remaining engineer regardless of where
        // they are, which is not a recommendation at all.
        if (region == null) {
            return roster.stream()
                    .filter(CAN_TAKE_WORK)
                    .filter(engineer -> engineer.hasSpecialization(required))
                    .map(engineer -> new EngineerMatch(engineer, MatchTier.EXACT))
                    .sorted(EngineerMatch.preferredOrder())
                    .collect(Collectors.toList());
        }

        Stream<EngineerMatch> exact = matches(roster, MatchTier.EXACT,
                engineer -> engineer.hasSpecialization(required)
                        && engineer.servesRegion(region));
        Stream<EngineerMatch> remote = matches(roster, MatchTier.OUT_OF_REGION,
                engineer -> engineer.hasSpecialization(required)
                        && !engineer.servesRegion(region));
        Stream<EngineerMatch> local = matches(roster, MatchTier.REGION_ONLY,
                engineer -> !engineer.hasSpecialization(required)
                        && engineer.servesRegion(region));

        return Stream.concat(exact, Stream.concat(remote, local))
                .sorted(EngineerMatch.preferredOrder())
                .collect(Collectors.toList());
    }

    private Stream<EngineerMatch> matches(List<NetworkEngineer> roster, MatchTier tier,
                                          Predicate<NetworkEngineer> fits) {
        return roster.stream()
                .filter(CAN_TAKE_WORK)
                .filter(fits)
                .map(engineer -> new EngineerMatch(engineer, tier));
    }

    /**
     * The top few candidates, which is what a console shows.
     *
     * @param limit how many to return; zero or less falls back to
     *              {@link #DEFAULT_SHORTLIST} and anything larger than
     *              {@link #MAX_SHORTLIST} is capped
     */
    public List<EngineerMatch> shortlist(List<NetworkEngineer> roster, Specialization required,
                                         Region region, int limit) {
        return rank(roster, required, region).stream()
                .limit(boundedLimit(limit))
                .collect(Collectors.toList());
    }

    /**
     * Section 16's worked example, exactly as written: the three engineers
     * with the lowest active workload who have the required specialization
     * and are currently available.
     *
     * <p>Region is deliberately not part of it, because the example does not
     * mention region.</p>
     */
    public List<NetworkEngineer> lowestWorkload(List<NetworkEngineer> roster,
                                                Specialization required, int count) {
        if (roster == null || required == null) {
            return Collections.emptyList();
        }
        return roster.stream()
                .filter(CAN_TAKE_WORK)
                .filter(engineer -> engineer.hasSpecialization(required))
                .sorted(NetworkEngineer.byWorkloadThenExperience())
                .limit(boundedLimit(count))
                .collect(Collectors.toList());
    }

    /**
     * The one engineer to hand the ticket to, if there is one.
     *
     * <p>Empty rather than an exception, because "nobody is free" is a
     * normal state of a support desk at three in the morning and the caller
     * decides what to do about it. Candidates found only by dropping the
     * skill requirement are excluded here; see
     * {@link MatchTier#isAutomatic()}.</p>
     */
    public Optional<EngineerMatch> best(List<NetworkEngineer> roster, Specialization required,
                                        Region region) {
        return rank(roster, required, region).stream()
                .filter(EngineerMatch::isAutomatic)
                .findFirst();
    }

    private static long boundedLimit(int requested) {
        if (requested <= 0) {
            return DEFAULT_SHORTLIST;
        }
        return Math.min(requested, MAX_SHORTLIST);
    }
}
