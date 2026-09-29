package com.amdocs.telecom.security;

import com.amdocs.telecom.exception.AuthenticationException;
import com.amdocs.telecom.exception.AuthorizationException;
import com.amdocs.telecom.exception.ErrorCode;
import com.amdocs.telecom.model.enums.Role;
import com.amdocs.telecom.util.AppLogger;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The single gate every privileged operation passes through.
 *
 * <p>Two questions get asked. Does this role hold the permission at all, and
 * for operations on a specific ticket, is this particular ticket any of their
 * business. A customer holding {@code VIEW_OWN_TICKETS} still must not read
 * somebody else's, and an engineer must not work a ticket assigned to another
 * engineer.</p>
 *
 * <p>Every refusal is logged. A pattern of denials for one account is the
 * sort of thing an administrator wants to be able to find afterwards.</p>
 */
public final class AccessControl {

    private AccessControl() {
        throw new AssertionError("AccessControl is not instantiable");
    }

    /* ---------- Asking ---------- */

    public static boolean isPermitted(Role role, Permission permission) {
        return permission != null && permission.isGrantedTo(role);
    }

    /**
     * Whether an active session holds a permission. An expired or signed out
     * session holds nothing, whatever its role.
     */
    public static boolean isPermitted(UserSession session, Permission permission) {
        return session != null && session.isActive() && isPermitted(session.getRole(), permission);
    }

    /* ---------- Demanding ---------- */

    /**
     * Lets the operation proceed, or stops it.
     *
     * @throws AuthenticationException when there is no usable session, which
     *         is a different failure from being signed in without the right
     * @throws AuthorizationException when the role does not hold the permission
     */
    public static void require(UserSession session, Permission permission) {
        if (session == null) {
            throw new AuthenticationException(ErrorCode.AUTH_SESSION_EXPIRED,
                    "You must sign in before performing this operation");
        }
        if (session.isSignedOut()) {
            throw new AuthenticationException(ErrorCode.AUTH_SESSION_EXPIRED,
                    "This session has been signed out. Please sign in again.");
        }
        if (session.isIdleTooLong()) {
            throw new AuthenticationException(ErrorCode.AUTH_SESSION_EXPIRED,
                    "This session has been idle too long. Please sign in again.");
        }
        if (!isPermitted(session.getRole(), permission)) {
            AppLogger.warn(AccessControl.class, "Access denied: '" + session.getUsername()
                    + "' as " + session.getRole() + " attempted " + permission.name());
            throw new AuthorizationException("A " + session.getRole().getDisplayName()
                    + " may not " + lowerFirst(permission.getDescription()));
        }
        session.touch();
    }

    /**
     * Guards an operation on one customer's data.
     *
     * <p>Staff act on behalf of any customer; a customer only ever on their
     * own. The permission is checked first, so the message a customer sees
     * for somebody else's record is about ownership rather than about role.</p>
     */
    public static void requireCustomerAccess(UserSession session, Permission permission,
                                             Long customerId) {
        require(session, permission);
        if (session.getRole() == Role.CUSTOMER && !session.isOwnCustomer(customerId)) {
            AppLogger.warn(AccessControl.class, "Access denied: '" + session.getUsername()
                    + "' attempted " + permission.name() + " on customer " + customerId);
            throw new AuthorizationException(
                    "You may only view and act on your own records.");
        }
    }

    /**
     * Guards an operation on one ticket.
     *
     * @param ticketCustomerId the customer the ticket belongs to
     * @param assignedEngineerId the engineer it is assigned to, may be null
     */
    public static void requireTicketAccess(UserSession session, Permission permission,
                                           Long ticketCustomerId, Long assignedEngineerId) {
        require(session, permission);

        if (session.getRole() == Role.CUSTOMER && !session.isOwnCustomer(ticketCustomerId)) {
            AppLogger.warn(AccessControl.class, "Access denied: customer '" + session.getUsername()
                    + "' attempted " + permission.name() + " on another customer's ticket");
            throw new AuthorizationException("This ticket was not raised by you.");
        }

        if (session.getRole() == Role.NETWORK_ENGINEER
                && !session.isOwnEngineerWork(assignedEngineerId)) {
            AppLogger.warn(AccessControl.class, "Access denied: engineer '" + session.getUsername()
                    + "' attempted " + permission.name() + " on a ticket assigned elsewhere");
            throw new AuthorizationException("This ticket is not assigned to you.");
        }
    }

    /* ---------- Describing ---------- */

    /**
     * Everything a role may do, in declaration order, for the access matrix
     * the console can print.
     */
    public static Set<Permission> permissionsOf(Role role) {
        Set<Permission> granted = EnumSet.noneOf(Permission.class);
        for (Permission permission : Permission.values()) {
            if (permission.isGrantedTo(role)) {
                granted.add(permission);
            }
        }
        return granted;
    }

    /**
     * The roles holding a permission, most privileged listed as declared,
     * used when explaining a refusal.
     */
    public static List<String> rolesHolding(Permission permission) {
        Comparator<Role> byDeclaration = Comparator.comparingInt(Role::ordinal);
        return permission.getAllowedRoles().stream()
                .sorted(byDeclaration)
                .map(Role::getDisplayName)
                .collect(Collectors.toList());
    }

    private static String lowerFirst(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }
}
