package com.amdocs.telecom.service.escalation;

import com.amdocs.telecom.model.enums.DescribableEnum;
import com.amdocs.telecom.model.enums.EscalationLevel;

/**
 * The reasons section 9 gives for escalating a ticket, and how far up the
 * ladder each one justifies going.
 *
 * <p>Section 9 opens with "if a ticket is approaching or exceeding SLA" and
 * then draws a four rung ladder, without saying which rung answers which
 * condition. Section 8 supplies the missing detail: a ticket is promised two
 * things, a response and a resolution, and the resolution promise has a
 * warning band before it expires. That is three ways of falling behind,
 * which is exactly the number of steps between the bottom rung and the top
 * one.</p>
 *
 * <p>So the mapping below invents no threshold of its own. It reads the
 * three conditions the SLA engine already computes and pairs each with the
 * rung it warrants, worst first.</p>
 */
public enum EscalationTrigger implements DescribableEnum {

    /**
     * The response window ran out, or the first response arrived after it
     * had. Nobody has answered the customer in the time promised, which is
     * the engineer's own commitment and so is the team lead's business.
     */
    RESPONSE_MISSED("ET1", "Response missed", EscalationLevel.TEAM_LEAD,
            "the response window was missed"),

    /**
     * The resolution window is nearly used up. The fix is still possible,
     * but it has stopped being likely without help.
     */
    RESOLUTION_AT_RISK("ET2", "Resolution at risk", EscalationLevel.NETWORK_MANAGER,
            "the resolution window is nearly used up"),

    /**
     * The resolution deadline has passed. The promise is already broken, so
     * this goes as far as the ladder goes.
     */
    RESOLUTION_BREACHED("ET3", "Resolution breached", EscalationLevel.OPERATIONS_MANAGER,
            "the resolution deadline has passed");

    private final String code;
    private final String displayName;
    private final EscalationLevel warrantedLevel;
    private final String reason;

    EscalationTrigger(String code, String displayName, EscalationLevel warrantedLevel,
                      String reason) {
        this.code = code;
        this.displayName = displayName;
        this.warrantedLevel = warrantedLevel;
        this.reason = reason;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    /**
     * The highest rung this condition justifies. A ticket already at or
     * above it is being handled at a level its SLA position warrants, and is
     * left alone.
     */
    public EscalationLevel warrants() {
        return warrantedLevel;
    }

    /**
     * The phrase written into {@code escalation_history.reason}, so somebody
     * reading the trail later sees why rather than only when.
     */
    public String getReason() {
        return reason;
    }
}
