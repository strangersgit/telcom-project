package com.amdocs.telecom.service.assignment;

/**
 * How well a candidate engineer fits the ticket that needs one.
 *
 * <p>Section 7 lists specialization, region, availability, experience and
 * workload as the things a recommendation should weigh, but not what to do
 * when nobody satisfies all of them. A critical enterprise link outage in a
 * region whose only transmission engineer is on leave still has to reach
 * somebody, so the search widens in steps and each candidate records which
 * step found it.</p>
 *
 * <p>Availability and workload are never relaxed: an engineer who is off
 * shift or already full is not a worse candidate, they are not a candidate.
 * Only skill and region bend.</p>
 *
 * <p>The constants are declared in preference order, best first, and the
 * recommendation ordering relies on that: an enum compares by declaration
 * position, so sorting by tier needs no comparator of its own. Adding a
 * tier means inserting it where it belongs rather than editing a ranking
 * table somewhere else.</p>
 */
public enum MatchTier {

    /**
     * The right skill, in the ticket's own region. What the system should
     * normally find.
     */
    EXACT("Exact match", "right skill, in region"),

    /**
     * The right skill, from another region. Remote hands on the correct
     * technology beat local hands on the wrong one, which is why this comes
     * before {@link #REGION_ONLY}.
     */
    OUT_OF_REGION("Out of region", "right skill, from another region"),

    /**
     * Local, but not a specialist in this technology. Offered so the service
     * desk can make a judgement, never chosen automatically.
     */
    REGION_ONLY("Different skill", "in region, different specialization");

    private final String displayName;
    private final String reason;

    MatchTier(String displayName, String reason) {
        this.displayName = displayName;
        this.reason = reason;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * A phrase for the console and the audit trail explaining why this
     * candidate appeared.
     */
    public String getReason() {
        return reason;
    }

    /**
     * Whether an automatic assignment may pick a candidate found at this
     * tier.
     *
     * <p>Handing a radio access fault to a broadband engineer might be the
     * right call, but it is a judgement about who can actually help, and
     * nothing here knows enough to make it. So the last tier is shown and
     * not taken.</p>
     */
    public boolean isAutomatic() {
        return this != REGION_ONLY;
    }
}
