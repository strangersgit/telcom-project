package com.amdocs.telecom.service.escalation;

import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.enums.EscalationLevel;
import com.amdocs.telecom.service.sla.SlaEvaluation;

import java.util.Optional;

/**
 * Decides, from a ticket's SLA standing alone, how far up the ladder it
 * ought to be.
 *
 * <p>Pure: no database, no clock of its own, no state. Everything it needs
 * arrives in the {@link SlaEvaluation} it is handed, which means the console
 * can ask "what would happen to this ticket" without anything happening to
 * it, and the verification harness can check every rule without a row being
 * written.</p>
 *
 * <p>Two ideas keep this simple. The first is that the warranted level is a
 * function of the ticket's SLA position rather than of its history: ask the
 * same question twice and you get the same answer, so running the sweep
 * again cannot march a ticket up the ladder for no reason. The second is
 * that escalation moves one rung at a time, matching
 * {@link EscalationLevel#next()} and {@code sp_escalate_ticket}. A ticket
 * that is three rungs behind takes three passes to catch up, and each pass
 * leaves its own line in the trail saying who it reached and why.</p>
 */
public final class EscalationPolicy {

    /**
     * Why this ticket has fallen behind, worst reason first, or empty when
     * it has not.
     *
     * <p>Only an open ticket can fall behind: a resolved one was judged
     * against its deadline when it was resolved and that verdict is final.
     * A ticket waiting on the customer is exempt from the at risk warning
     * but not from a breach, which is the distinction
     * {@link SlaEvaluation#needsAttention()} already draws for the SLA
     * monitor; escalation follows it rather than inventing a second rule.</p>
     */
    public Optional<EscalationTrigger> triggerFor(SlaEvaluation verdict) {
        if (verdict == null || !verdict.isOpen()) {
            return Optional.empty();
        }
        if (verdict.isBreached()) {
            return Optional.of(EscalationTrigger.RESOLUTION_BREACHED);
        }
        if (verdict.isAtRisk() && verdict.needsAttention()) {
            return Optional.of(EscalationTrigger.RESOLUTION_AT_RISK);
        }
        if (verdict.isResponseBreached()) {
            return Optional.of(EscalationTrigger.RESPONSE_MISSED);
        }
        return Optional.empty();
    }

    /**
     * The rung this ticket's SLA position justifies. The bottom rung when
     * nothing is wrong, because every ticket starts with its engineer.
     */
    public EscalationLevel warrantedLevel(SlaEvaluation verdict) {
        return triggerFor(verdict)
                .map(EscalationTrigger::warrants)
                .orElse(EscalationLevel.ENGINEER);
    }

    /**
     * Whether the ticket is being handled below the level its SLA position
     * calls for.
     */
    public boolean isBehind(EscalationLevel current, SlaEvaluation verdict) {
        return nextStepFor(current, verdict).isPresent();
    }

    /**
     * The single rung to move the ticket to now, or empty when it is
     * already at or above the level it warrants, and when it has run out of
     * ladder.
     *
     * @param current the ticket's level, treated as the bottom rung when a
     *                row somehow has none
     */
    public Optional<EscalationLevel> nextStepFor(EscalationLevel current, SlaEvaluation verdict) {
        EscalationLevel standing = current == null ? EscalationLevel.ENGINEER : current;
        EscalationLevel warranted = warrantedLevel(verdict);
        if (warranted.getLevel() <= standing.getLevel()) {
            return Optional.empty();
        }
        return standing.next();
    }

    /**
     * The whole judgement on one ticket: the reason it has fallen behind
     * and the rung to move it to, or empty when there is nothing to do.
     *
     * <p>Empty covers three cases that need no distinguishing here, because
     * all three mean leave it alone: the SLA has no complaint, the ticket is
     * already being handled at the level its standing warrants, and it has
     * run out of ladder.</p>
     *
     * @param verdict the ticket's SLA standing, normally
     *                {@code slaService.evaluate(ticket)}
     */
    public Optional<EscalationCandidate> assess(TroubleTicket ticket, SlaEvaluation verdict) {
        if (ticket == null) {
            return Optional.empty();
        }
        Optional<EscalationTrigger> trigger = triggerFor(verdict);
        if (!trigger.isPresent()) {
            return Optional.empty();
        }
        EscalationLevel from = ticket.getEscalationLevel() == null
                ? EscalationLevel.ENGINEER
                : ticket.getEscalationLevel();
        Optional<EscalationLevel> to = nextStepFor(from, verdict);
        if (!to.isPresent()) {
            return Optional.empty();
        }
        return Optional.of(
                new EscalationCandidate(ticket, verdict, trigger.get(), from, to.get()));
    }

    /**
     * How the rung a ticket sits on compares with the rung it deserves,
     * phrased for a console rather than for a log.
     */
    public String describe(EscalationLevel current, SlaEvaluation verdict) {
        EscalationLevel standing = current == null ? EscalationLevel.ENGINEER : current;
        Optional<EscalationTrigger> trigger = triggerFor(verdict);
        if (!trigger.isPresent()) {
            return "Held at " + standing.getDisplayName() + ", which its SLA standing supports.";
        }
        EscalationLevel warranted = trigger.get().warrants();
        if (warranted.getLevel() <= standing.getLevel()) {
            return "Already at " + standing.getDisplayName() + " although "
                    + trigger.get().getReason() + ".";
        }
        return "At " + standing.getDisplayName() + " but " + trigger.get().getReason()
                + ", which calls for " + warranted.getDisplayName() + ".";
    }
}
