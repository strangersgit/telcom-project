package com.amdocs.telecom.security;

import com.amdocs.telecom.model.Displayable;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * What a login step produced: the outcome, a sentence safe to show the user,
 * and whatever the next step needs.
 *
 * <p>Immutable, and built only through the factory methods below so that an
 * authenticated result cannot exist without a session, or a failed one with
 * a session attached.</p>
 */
public final class LoginResult {

    private final LoginOutcome outcome;
    private final String message;
    private final UserSession session;
    private final String otpDelivery;
    private final int attemptsRemaining;
    private final LocalDateTime lockedUntil;
    private final boolean passwordChangeRequired;

    private LoginResult(LoginOutcome outcome, String message, UserSession session,
                        String otpDelivery, int attemptsRemaining,
                        LocalDateTime lockedUntil, boolean passwordChangeRequired) {
        this.outcome = outcome;
        this.message = message;
        this.session = session;
        this.otpDelivery = otpDelivery;
        this.attemptsRemaining = attemptsRemaining;
        this.lockedUntil = lockedUntil;
        this.passwordChangeRequired = passwordChangeRequired;
    }

    /* ----------
     * Factories. Intended for
     * {@link com.amdocs.telecom.service.AuthenticationService} only; nothing
     * else has any business declaring that a login succeeded.
     * ---------- */

    /**
     * The password was right and a code has been sent.
     *
     * @param otpDelivery the simulated message to show, containing the code
     */
    public static LoginResult otpRequired(String otpDelivery) {
        return new LoginResult(LoginOutcome.OTP_REQUIRED,
                "Enter the verification code that was sent to you.",
                null, otpDelivery, -1, null, false);
    }

    public static LoginResult authenticated(UserSession session, boolean passwordChangeRequired) {
        String greeting = "Welcome, " + session.getDisplayName()
                + ". You are signed in as " + session.getRole().getDisplayName() + ".";
        return new LoginResult(LoginOutcome.AUTHENTICATED, greeting,
                session, null, -1, null, passwordChangeRequired);
    }

    /**
     * A wrong password, with how many tries are left before the lock.
     */
    public static LoginResult invalidCredentials(int attemptsRemaining) {
        StringBuilder message = new StringBuilder(LoginOutcome.INVALID_CREDENTIALS.getDescription());
        message.append('.');
        if (attemptsRemaining == 1) {
            message.append(" One more failed attempt will lock the account.");
        } else if (attemptsRemaining > 1) {
            message.append(' ').append(attemptsRemaining).append(" attempts remaining.");
        }
        return new LoginResult(LoginOutcome.INVALID_CREDENTIALS, message.toString(),
                null, null, attemptsRemaining, null, false);
    }

    public static LoginResult lockedOut(LocalDateTime lockedUntil) {
        String message = lockedUntil == null
                ? "This account is locked. Ask an administrator to unlock it."
                : "This account is locked until " + Displayable.formatDateTime(lockedUntil) + ".";
        return new LoginResult(LoginOutcome.ACCOUNT_LOCKED, message,
                null, null, 0, lockedUntil, false);
    }

    public static LoginResult failure(LoginOutcome outcome, String message) {
        return new LoginResult(outcome, message, null, null, -1, null, false);
    }

    public static LoginResult failure(LoginOutcome outcome) {
        return failure(outcome, outcome.getDescription() + ".");
    }

    /* ---------- Reading ---------- */

    public LoginOutcome getOutcome() {
        return outcome;
    }

    /**
     * A sentence that can be shown as it is. Never says which of the username
     * or the password was wrong.
     */
    public String getMessage() {
        return message;
    }

    public Optional<UserSession> getSession() {
        return Optional.ofNullable(session);
    }

    /**
     * The simulated delivery message, present only when a code was just sent.
     */
    public Optional<String> getOtpDelivery() {
        return Optional.ofNullable(otpDelivery);
    }

    /**
     * Tries left before the account locks, or empty when that does not apply
     * to this outcome.
     */
    public Optional<Integer> getAttemptsRemaining() {
        return attemptsRemaining < 0 ? Optional.<Integer>empty() : Optional.of(attemptsRemaining);
    }

    public Optional<LocalDateTime> getLockedUntil() {
        return Optional.ofNullable(lockedUntil);
    }

    /**
     * Whether the user must set a new password before doing anything else.
     */
    public boolean isPasswordChangeRequired() {
        return passwordChangeRequired;
    }

    public boolean isAuthenticated() {
        return outcome.isAuthenticated();
    }

    public boolean needsOtp() {
        return outcome.needsOtp();
    }

    @Override
    public String toString() {
        return "LoginResult[" + outcome + "]";
    }
}
