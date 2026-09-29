package com.amdocs.telecom.model;

import com.amdocs.telecom.model.enums.AccountStatus;
import com.amdocs.telecom.model.enums.Role;
import com.amdocs.telecom.util.AppConstants;

import java.time.LocalDateTime;

/**
 * A login account.
 *
 * <p>Service desk administrators and network managers exist as an account
 * alone; customers and engineers each link to one. The stored credential is
 * a salted hash, never a password, and neither the hash nor the salt is ever
 * included in any rendering of this object.</p>
 */
public class UserAccount extends AbstractParty {

    private static final long serialVersionUID = 1L;

    private String username;
    private String passwordHash;
    private String passwordSalt;
    private Role role;
    private AccountStatus accountStatus = AccountStatus.ACTIVE;
    private int failedLoginAttempts;
    private LocalDateTime lockedUntil;
    private LocalDateTime lastLoginDate;
    private boolean mustChangePassword;

    public UserAccount() {
        super();
    }

    public UserAccount(String username, String fullName, String email, Role role) {
        super(null, fullName, email);
        this.username = username;
        this.role = role;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * The salted hash of the password. Never the password itself.
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getPasswordSalt() {
        return passwordSalt;
    }

    public void setPasswordSalt(String passwordSalt) {
        this.passwordSalt = passwordSalt;
    }

    @Override
    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public AccountStatus getAccountStatus() {
        return accountStatus;
    }

    public void setAccountStatus(AccountStatus accountStatus) {
        this.accountStatus = accountStatus;
    }

    public int getFailedLoginAttempts() {
        return failedLoginAttempts;
    }

    public void setFailedLoginAttempts(int failedLoginAttempts) {
        this.failedLoginAttempts = failedLoginAttempts;
    }

    public LocalDateTime getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(LocalDateTime lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public LocalDateTime getLastLoginDate() {
        return lastLoginDate;
    }

    public void setLastLoginDate(LocalDateTime lastLoginDate) {
        this.lastLoginDate = lastLoginDate;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    /**
     * True while a temporary lock from failed attempts is still in force.
     * A lock whose expiry has passed no longer counts, which is what lets an
     * account free itself without an administrator.
     */
    public boolean isCurrentlyLocked() {
        if (accountStatus == AccountStatus.LOCKED) {
            return lockedUntil == null || lockedUntil.isAfter(LocalDateTime.now());
        }
        return false;
    }

    /**
     * Whether a login may proceed, taking an expired lock into account.
     */
    public boolean canAttemptLogin() {
        if (accountStatus == AccountStatus.DISABLED
                || accountStatus == AccountStatus.PENDING_ACTIVATION) {
            return false;
        }
        return !isCurrentlyLocked();
    }

    /**
     * Whether the seeded sentinel is still in place, meaning this account has
     * never had a real password set.
     */
    public boolean hasUsablePassword() {
        return passwordHash != null
                && !passwordHash.isEmpty()
                && !AppConstants.PENDING_PASSWORD_SENTINEL.equals(passwordHash);
    }

    @Override
    public String getBusinessKey() {
        return username;
    }

    @Override
    public String getAuditEntityType() {
        return "USER_ACCOUNT";
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-14s %-22s %-26s %-12s",
                Displayable.orDash(username),
                Displayable.truncate(getName(), 22),
                role == null ? "-" : role.getDisplayName(),
                accountStatus == null ? "-" : accountStatus.getDisplayName());
    }

    @Override
    public String toDetailBlock() {
        StringBuilder builder = new StringBuilder();
        builder.append(Displayable.labelled("Username", username)).append(System.lineSeparator());
        builder.append(Displayable.labelled("Name", getName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Email", getEmail())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Role",
                role == null ? null : role.getDisplayName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Status",
                accountStatus == null ? null : accountStatus.getDisplayName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Failed attempts", failedLoginAttempts)).append(System.lineSeparator());
        builder.append(Displayable.labelled("Locked until",
                Displayable.formatDateTime(lockedUntil))).append(System.lineSeparator());
        builder.append(Displayable.labelled("Last login",
                Displayable.formatDateTime(lastLoginDate)));
        return builder.toString();
    }
}
