package com.amdocs.telecom.security;

import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.UserAccount;
import com.amdocs.telecom.model.enums.Role;
import com.amdocs.telecom.util.AppConstants;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Who is signed in, and what they are allowed to reach.
 *
 * <p>Created only by {@link com.amdocs.telecom.service.AuthenticationService}
 * once every step of the login has passed. Holding one is therefore proof
 * that the CAPTCHA, the password and the one time password were all
 * satisfied.</p>
 *
 * <p>A customer's and an engineer's own record is resolved once, at login,
 * and carried here. Every later check of "is this your ticket" compares
 * against these rather than going back to the database.</p>
 */
public final class UserSession {

    private final Long userId;
    private final String username;
    private final String displayName;
    private final Role role;
    private final LocalDateTime loginTime;

    /** The login_history row for this session, stamped again on sign out. */
    private final Long loginHistoryId;

    /** Set when the account belongs to a customer, absent otherwise. */
    private final Long customerId;

    /** Set when the account belongs to an engineer, absent otherwise. */
    private final Long engineerId;

    private LocalDateTime lastActivity;
    private boolean signedOut;
    private boolean passwordChangeRequired;

    /**
     * Intended to be called only by
     * {@link com.amdocs.telecom.service.AuthenticationService}, once every
     * login step has passed.
     */
    public UserSession(UserAccount account, Long loginHistoryId, Long customerId, Long engineerId) {
        this.userId = account.getId();
        this.username = account.getUsername();
        this.displayName = account.getName();
        this.role = account.getRole();
        this.passwordChangeRequired = account.isMustChangePassword();
        this.loginHistoryId = loginHistoryId;
        this.customerId = customerId;
        this.engineerId = engineerId;
        this.loginTime = LocalDateTime.now();
        this.lastActivity = this.loginTime;
    }

    public Long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Role getRole() {
        return role;
    }

    public LocalDateTime getLoginTime() {
        return loginTime;
    }

    public Optional<Long> getLoginHistoryId() {
        return Optional.ofNullable(loginHistoryId);
    }

    public Optional<Long> getCustomerId() {
        return Optional.ofNullable(customerId);
    }

    public Optional<Long> getEngineerId() {
        return Optional.ofNullable(engineerId);
    }

    public LocalDateTime getLastActivity() {
        return lastActivity;
    }

    /* ---------- Validity ---------- */

    /**
     * Records that the user did something, which pushes the idle timeout out.
     */
    public void touch() {
        this.lastActivity = LocalDateTime.now();
    }

    public boolean isIdleTooLong() {
        return Duration.between(lastActivity, LocalDateTime.now()).toMinutes()
                >= AppConstants.SESSION_IDLE_MINUTES;
    }

    public boolean isSignedOut() {
        return signedOut;
    }

    /**
     * Whether this session may still be used for anything.
     */
    public boolean isActive() {
        return !signedOut && !isIdleTooLong();
    }

    /**
     * Marks the session finished. Called by the authentication service, which
     * also stamps the logout time on the login history row.
     */
    public void markSignedOut() {
        this.signedOut = true;
    }

    /**
     * Whether the user is still working with a password an administrator set
     * for them, which every screen refuses to go past until it is replaced.
     */
    public boolean isPasswordChangeRequired() {
        return passwordChangeRequired;
    }

    /**
     * Cleared by the authentication service once a new password is accepted.
     */
    public void clearPasswordChangeRequired() {
        this.passwordChangeRequired = false;
    }

    /* ---------- Convenience ---------- */

    public boolean hasPermission(Permission permission) {
        return permission.isGrantedTo(role);
    }

    /**
     * Whether this session owns the given customer record. A customer may
     * only ever see their own; staff are not customers and so own none.
     */
    public boolean isOwnCustomer(Long candidateCustomerId) {
        return customerId != null && customerId.equals(candidateCustomerId);
    }

    /**
     * Whether the given ticket is assigned to this engineer.
     */
    public boolean isOwnEngineerWork(Long candidateEngineerId) {
        return engineerId != null && engineerId.equals(candidateEngineerId);
    }

    /**
     * One line for the top of every dashboard.
     */
    public String describe() {
        return displayName + " (" + username + ")  |  " + role.getDisplayName()
                + "  |  signed in " + Displayable.formatDateTime(loginTime);
    }

    @Override
    public String toString() {
        return "UserSession[username=" + username + ", role=" + role
                + ", loginTime=" + loginTime + ", signedOut=" + signedOut + "]";
    }
}
