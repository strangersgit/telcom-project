package com.amdocs.telecom.validation;

import com.amdocs.telecom.exception.BusinessException;
import com.amdocs.telecom.exception.ErrorCode;
import com.amdocs.telecom.model.enums.TicketStatus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Which status changes a ticket is allowed to make.
 *
 * <p>Section 4 of the case study lists the eight states but not the moves
 * between them, and section 14 lists what the service desk can do without
 * saying when. The graph below is therefore a design decision rather than a
 * transcription, and each edge has a reason:</p>
 *
 * <ul>
 *   <li>{@code OPEN} can only be assigned or cancelled. It cannot jump
 *       straight to {@code IN_PROGRESS} because nobody owns it yet, and
 *       section 19 makes assignment the step that gives a ticket an
 *       engineer.</li>
 *   <li>Anything being worked on can go to {@code PENDING_CUSTOMER} and
 *       back, be escalated, or be resolved.</li>
 *   <li>{@code ESCALATED} does not return to {@code ASSIGNED}. Escalation
 *       raises the level the ticket is handled at; it does not hand the
 *       ticket back.</li>
 *   <li>{@code RESOLVED} can be closed, or reopened to
 *       {@code IN_PROGRESS} when the fix did not hold. It reopens to
 *       {@code IN_PROGRESS} rather than {@code OPEN} because the engineer is
 *       still assigned.</li>
 *   <li>{@code RESOLVED} cannot be cancelled. Cancelling says the ticket
 *       should never have existed, which is not true of one that has been
 *       worked and fixed.</li>
 *   <li>{@code CLOSED} and {@code CANCELLED} are final.</li>
 * </ul>
 *
 * <p>A state is never allowed to move to itself. A change that changes
 * nothing would still write a status history row, and a trail full of
 * entries recording nothing is worse than no trail.</p>
 */
public final class TicketLifecycle {

    private TicketLifecycle() {
        throw new AssertionError("TicketLifecycle is not instantiable");
    }

    private static final Map<TicketStatus, Set<TicketStatus>> ALLOWED = buildGraph();

    private static Map<TicketStatus, Set<TicketStatus>> buildGraph() {
        Map<TicketStatus, Set<TicketStatus>> graph =
                new EnumMap<TicketStatus, Set<TicketStatus>>(TicketStatus.class);

        graph.put(TicketStatus.OPEN,
                EnumSet.of(TicketStatus.ASSIGNED, TicketStatus.CANCELLED));
        graph.put(TicketStatus.ASSIGNED,
                EnumSet.of(TicketStatus.IN_PROGRESS, TicketStatus.PENDING_CUSTOMER,
                        TicketStatus.ESCALATED, TicketStatus.CANCELLED));
        graph.put(TicketStatus.IN_PROGRESS,
                EnumSet.of(TicketStatus.PENDING_CUSTOMER, TicketStatus.ESCALATED,
                        TicketStatus.RESOLVED, TicketStatus.CANCELLED));
        graph.put(TicketStatus.PENDING_CUSTOMER,
                EnumSet.of(TicketStatus.IN_PROGRESS, TicketStatus.ESCALATED,
                        TicketStatus.RESOLVED, TicketStatus.CANCELLED));
        graph.put(TicketStatus.ESCALATED,
                EnumSet.of(TicketStatus.IN_PROGRESS, TicketStatus.PENDING_CUSTOMER,
                        TicketStatus.RESOLVED, TicketStatus.CANCELLED));
        graph.put(TicketStatus.RESOLVED,
                EnumSet.of(TicketStatus.CLOSED, TicketStatus.IN_PROGRESS));
        graph.put(TicketStatus.CLOSED, EnumSet.noneOf(TicketStatus.class));
        graph.put(TicketStatus.CANCELLED, EnumSet.noneOf(TicketStatus.class));

        // Wrapped so a caller cannot quietly widen the rules at runtime.
        for (Map.Entry<TicketStatus, Set<TicketStatus>> entry : graph.entrySet()) {
            entry.setValue(Collections.unmodifiableSet(entry.getValue()));
        }
        return Collections.unmodifiableMap(graph);
    }

    /**
     * Everywhere a ticket in this state may go next. Empty for a final
     * state, which is what a menu uses to decide there is nothing to offer.
     */
    public static Set<TicketStatus> nextStatesFrom(TicketStatus current) {
        if (current == null) {
            return Collections.emptySet();
        }
        Set<TicketStatus> allowed = ALLOWED.get(current);
        return allowed == null ? Collections.<TicketStatus>emptySet() : allowed;
    }

    public static boolean canMove(TicketStatus from, TicketStatus to) {
        return from != null && to != null && nextStatesFrom(from).contains(to);
    }

    /**
     * Lets a transition proceed, or explains why it cannot.
     *
     * @throws BusinessException naming both states and what is actually
     *         available, so the caller is told what to do instead of only
     *         what they may not do
     */
    public static void requireTransition(String ticketNumber, TicketStatus from,
                                         TicketStatus to) {
        if (canMove(from, to)) {
            return;
        }
        throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                describeRefusal(ticketNumber, from, to));
    }

    private static String describeRefusal(String ticketNumber, TicketStatus from,
                                          TicketStatus to) {
        String ticket = ticketNumber == null ? "The ticket" : "Ticket " + ticketNumber;
        if (from == null || to == null) {
            return ticket + " needs both a current and a target status";
        }
        if (from == to) {
            return ticket + " is already " + from.getDisplayName();
        }
        Set<TicketStatus> available = nextStatesFrom(from);
        if (available.isEmpty()) {
            return ticket + " is " + from.getDisplayName()
                    + ", which is final. No further change is possible.";
        }
        return ticket + " cannot move from " + from.getDisplayName() + " to "
                + to.getDisplayName() + ". Available: " + describe(available) + ".";
    }

    /**
     * Whether this move is a ticket coming back from being resolved, which
     * the caller has to treat differently: the previous resolution has to be
     * cleared or the ticket would look both unresolved and resolved.
     */
    public static boolean isReopen(TicketStatus from, TicketStatus to) {
        return from == TicketStatus.RESOLVED && to == TicketStatus.IN_PROGRESS;
    }

    /**
     * The whole graph, for the console and the traceability matrix.
     */
    public static String describeGraph() {
        StringBuilder builder = new StringBuilder();
        for (TicketStatus status : TicketStatus.values()) {
            if (builder.length() > 0) {
                builder.append(System.lineSeparator());
            }
            Set<TicketStatus> available = nextStatesFrom(status);
            builder.append(String.format("  %-18s -> %s", status.getDisplayName(),
                    available.isEmpty() ? "(final)" : describe(available)));
        }
        return builder.toString();
    }

    private static String describe(Set<TicketStatus> states) {
        StringBuilder builder = new StringBuilder();
        for (TicketStatus state : states) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(state.getDisplayName());
        }
        return builder.toString();
    }
}
