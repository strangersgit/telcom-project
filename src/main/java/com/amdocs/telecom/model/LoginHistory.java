package com.amdocs.telecom.model;

import com.amdocs.telecom.model.enums.LoginStatus;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * One login attempt, successful or otherwise, as required by section 2 of
 * the case study.
 *
 * <p>The username is stored alongside the user id because an attempt against
 * a username that does not exist still has to be recorded, and in that case
 * there is no id to record.</p>
 */
public class LoginHistory extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String username;
    private LocalDateTime loginTime;
    private LocalDateTime logoutTime;
    private LoginStatus loginStatus;
    private String failureReason;
    private String ipAddress;

    public LoginHistory() {
        super();
    }

    public LoginHistory(Long userId, String username, LoginStatus loginStatus, String failureReason) {
        this.userId = userId;
        this.username = username;
        this.loginStatus = loginStatus;
        this.failureReason = failureReason;
        this.loginTime = stampNow();
    }

    /**
     * Records an attempt against a username that could not be resolved to an
     * account.
     */
    public static LoginHistory forUnknownUser(String username, String failureReason) {
        return new LoginHistory(null, username, LoginStatus.FAILED, failureReason);
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public LocalDateTime getLoginTime() {
        return loginTime;
    }

    public void setLoginTime(LocalDateTime loginTime) {
        this.loginTime = loginTime;
    }

    public LocalDateTime getLogoutTime() {
        return logoutTime;
    }

    public void setLogoutTime(LocalDateTime logoutTime) {
        this.logoutTime = logoutTime;
    }

    public LoginStatus getLoginStatus() {
        return loginStatus;
    }

    public void setLoginStatus(LoginStatus loginStatus) {
        this.loginStatus = loginStatus;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public boolean isSuccessful() {
        return loginStatus == LoginStatus.SUCCESS;
    }

    /**
     * A successful login that has not yet been signed out.
     */
    public boolean isSessionOpen() {
        return isSuccessful() && logoutTime == null;
    }

    /**
     * Length of the session in minutes, empty while it is still open.
     */
    public Optional<Long> getSessionMinutes() {
        if (loginTime == null || logoutTime == null) {
            return Optional.empty();
        }
        return Optional.of(ChronoUnit.MINUTES.between(loginTime, logoutTime));
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-18s %-16s %-16s %-18s %s",
                Displayable.formatDateTime(loginTime),
                Displayable.truncate(username, 16),
                loginStatus == null ? "-" : loginStatus.getDisplayName(),
                Displayable.formatDateTime(logoutTime),
                Displayable.truncate(failureReason, 30));
    }
}
