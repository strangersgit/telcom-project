package com.amdocs.telecom.controller;

import com.amdocs.telecom.dto.TicketDetailDTO;
import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.TroubleTicket;

/**
 * How a ticket is laid out on screen, in the columns each audience needs.
 *
 * <h3>Why the customer sees different columns</h3>
 *
 * <p>Section 13 names exactly what a customer may see of their own
 * ticket: number, service, priority, status, engineer, SLA status and
 * resolution. That is not the service desk's list, which leads with the
 * customer, and printing the desk's list to a customer would put their
 * own account number in every row of a list of their own tickets while
 * leaving out the service they are actually complaining about.</p>
 *
 * <p>Kept here rather than in the DTO because these are decisions about a
 * screen. The DTO already knows how to describe itself; what differs
 * between the two audiences is which parts of that belong in front of
 * which reader.</p>
 */
public final class TicketView {

    private TicketView() {
        throw new AssertionError("TicketView is not instantiable");
    }

    /** How much of a resolution fits in a list before it needs cutting. */
    private static final int RESOLUTION_WIDTH = 28;

    /* ---------- The customer's own tickets, section 13 ---------- */

    public static String customerHeading() {
        return String.format("%-16s %-22s %-10s %-16s %-10s %-12s %s",
                "TICKET", "SERVICE", "PRIORITY", "STATUS", "ENGINEER", "SLA", "RESOLUTION");
    }

    public static String customerRow(TicketDetailDTO ticket) {
        return String.format("%-16s %-22s %-10s %-16s %-10s %-12s %s",
                Displayable.orDash(ticket.getTicketNumber()),
                Displayable.truncate(Displayable.orDash(ticket.getServiceName()), 22),
                ticket.getPriority() == null ? "-" : ticket.getPriority().getDisplayName(),
                ticket.getStatus() == null ? "-" : ticket.getStatus().getDisplayName(),
                Displayable.orDash(ticket.getEngineerCode()),
                ticket.getLiveSlaStatus() == null ? "-"
                        : ticket.getLiveSlaStatus().getDisplayName(),
                Displayable.truncate(Displayable.orDash(ticket.getResolution()),
                        RESOLUTION_WIDTH));
    }

    /* ---------- The work queue, sections 14 and 10 ---------- */

    public static String queueHeading() {
        return String.format("%-16s %-12s %-18s %-10s %-16s %-12s %s",
                "TICKET", "CUSTOMER", "CATEGORY", "PRIORITY", "STATUS", "ENGINEER", "SLA");
    }

    /**
     * The same columns as {@link #queueHeading()}, which
     * {@link TicketDetailDTO#toSummaryLine()} already produces.
     */
    public static String queueRow(TicketDetailDTO ticket) {
        return ticket.toSummaryLine();
    }

    /* ---------- Raw tickets, where no join has been done ---------- */

    public static String plainHeading() {
        return String.format("%-16s %-18s %-10s %-16s %-14s",
                "TICKET", "CATEGORY", "PRIORITY", "STATUS", "SLA DEADLINE");
    }

    public static String plainRow(TroubleTicket ticket) {
        return ticket.toSummaryLine();
    }
}
