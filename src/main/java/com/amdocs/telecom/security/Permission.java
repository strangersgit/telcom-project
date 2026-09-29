package com.amdocs.telecom.security;

import com.amdocs.telecom.model.enums.Role;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Every privileged operation in the system, with the roles allowed to perform
 * it declared alongside it.
 *
 * <p>Keeping the two together means the access rule for an operation is
 * visible at the point the operation is named, rather than in a table
 * somewhere else that can drift out of step with it.</p>
 *
 * <p>The four roles come straight from section 1 of the case study: a customer
 * raises and follows their own incidents, a service desk administrator
 * triages and assigns, an engineer works what they are given, and a manager
 * oversees the whole operation.</p>
 */
public enum Permission {

    /* ---------- Raising and viewing ---------- */

    RAISE_TICKET("Raise a trouble ticket",
            Role.CUSTOMER, Role.SERVICE_DESK),

    VIEW_OWN_TICKETS("View tickets raised by oneself",
            Role.CUSTOMER),

    VIEW_ASSIGNED_TICKETS("View tickets assigned to oneself",
            Role.NETWORK_ENGINEER),

    VIEW_ALL_TICKETS("View any ticket in the system",
            Role.SERVICE_DESK, Role.NETWORK_MANAGER),

    SEARCH_TICKETS("Search and filter the ticket queue",
            Role.SERVICE_DESK, Role.NETWORK_ENGINEER, Role.NETWORK_MANAGER),

    /* ---------- Working a ticket ---------- */

    ASSIGN_ENGINEER("Assign or reassign an engineer",
            Role.SERVICE_DESK, Role.NETWORK_MANAGER),

    UPDATE_TICKET_STATUS("Move a ticket to another status",
            Role.SERVICE_DESK, Role.NETWORK_ENGINEER, Role.NETWORK_MANAGER),

    /**
     * Section 14's "Update Priority", kept separate from moving the status
     * because it changes what the operator has promised. Not held by the
     * engineer: an engineer deciding a ticket is urgent would be deciding
     * their own deadline.
     */
    UPDATE_TICKET_PRIORITY("Change the priority a ticket is handled at",
            Role.SERVICE_DESK, Role.NETWORK_MANAGER),

    RECORD_DIAGNOSIS("Record root cause and diagnosis notes",
            Role.NETWORK_ENGINEER),

    RESOLVE_TICKET("Resolve a ticket with a resolution code",
            Role.NETWORK_ENGINEER, Role.SERVICE_DESK),

    CLOSE_TICKET("Close a resolved ticket",
            Role.SERVICE_DESK, Role.NETWORK_MANAGER),

    CANCEL_TICKET("Cancel a ticket that should not have been raised",
            Role.SERVICE_DESK, Role.NETWORK_MANAGER),

    ESCALATE_TICKET("Escalate a ticket up the ladder",
            Role.SERVICE_DESK, Role.NETWORK_ENGINEER, Role.NETWORK_MANAGER),

    /* ---------- Customer facing ---------- */

    SUBMIT_FEEDBACK("Rate the handling of a resolved ticket",
            Role.CUSTOMER),

    VIEW_NOTIFICATIONS("Read one's own notifications",
            Role.CUSTOMER, Role.SERVICE_DESK, Role.NETWORK_ENGINEER, Role.NETWORK_MANAGER),

    /* ---------- Administration ---------- */

    MANAGE_CUSTOMERS("Create and amend customer records",
            Role.SERVICE_DESK),

    MANAGE_SERVICES("Create and amend telecom services",
            Role.SERVICE_DESK),

    MANAGE_ENGINEERS("Amend engineer skills, region and availability",
            Role.NETWORK_MANAGER),

    MANAGE_SLA_CONFIGURATION("Change the SLA windows",
            Role.NETWORK_MANAGER),

    MANAGE_USERS("Create login accounts, unlock and disable them",
            Role.SERVICE_DESK, Role.NETWORK_MANAGER),

    /* ---------- Oversight ---------- */

    VIEW_DASHBOARD("Open an operational dashboard",
            Role.CUSTOMER, Role.SERVICE_DESK, Role.NETWORK_ENGINEER, Role.NETWORK_MANAGER),

    VIEW_REPORTS("Run the operational reports",
            Role.SERVICE_DESK, Role.NETWORK_MANAGER),

    EXPORT_REPORTS("Write a report out to CSV or text",
            Role.SERVICE_DESK, Role.NETWORK_MANAGER),

    VIEW_ANALYTICS("Open the analytics summaries",
            Role.NETWORK_MANAGER),

    VIEW_AUDIT_LOG("Read the audit trail",
            Role.NETWORK_MANAGER),

    VIEW_LOGIN_HISTORY("Read login history for any account",
            Role.SERVICE_DESK, Role.NETWORK_MANAGER),

    /* ---------- Background processing ---------- */

    PROCESS_NETWORK_EVENTS("Start and stop network event processing",
            Role.SERVICE_DESK, Role.NETWORK_MANAGER);

    private final String description;
    private final Set<Role> allowedRoles;

    Permission(String description, Role... allowedRoles) {
        this.description = description;
        this.allowedRoles = Collections.unmodifiableSet(
                allowedRoles.length == 0 ? EnumSet.noneOf(Role.class)
                        : EnumSet.copyOf(java.util.Arrays.asList(allowedRoles)));
    }

    public String getDescription() {
        return description;
    }

    /**
     * The roles that hold this permission. Unmodifiable, so a caller cannot
     * widen the policy at run time.
     */
    public Set<Role> getAllowedRoles() {
        return allowedRoles;
    }

    public boolean isGrantedTo(Role role) {
        return role != null && allowedRoles.contains(role);
    }
}
