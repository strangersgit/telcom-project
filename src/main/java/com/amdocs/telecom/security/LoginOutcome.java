package com.amdocs.telecom.security;

import com.amdocs.telecom.model.enums.LoginStatus;

/**
 * How a login attempt ended.
 *
 * <p>Each outcome knows which {@link LoginStatus} it should be recorded as,
 * so the login history cannot drift out of step with what the user was told.
 * {@link #OTP_REQUIRED} is the one outcome that records nothing, because the
 * attempt is still in progress at that point.</p>
 */
public enum LoginOutcome {

    /** Password accepted; a one time password has been sent. */
    OTP_REQUIRED("Verification code sent", null),

    /** Every step passed and a session now exists. */
    AUTHENTICATED("Signed in", LoginStatus.SUCCESS),

    CAPTCHA_FAILED("CAPTCHA response was wrong or expired", LoginStatus.CAPTCHA_FAILED),

    /**
     * Covers both an unknown username and a wrong password. Which of the two
     * it was is deliberately not distinguished, so the prompt cannot be used
     * to discover which usernames exist.
     */
    INVALID_CREDENTIALS("Username or password is incorrect", LoginStatus.FAILED),

    ACCOUNT_LOCKED("Account is locked", LoginStatus.LOCKED),

    ACCOUNT_DISABLED("Account has been disabled", LoginStatus.FAILED),

    ACCOUNT_NOT_ACTIVATED("Account has not been activated", LoginStatus.FAILED),

    /** The account still holds the seeded sentinel instead of a real hash. */
    PASSWORD_NOT_SET("No password has been set for this account", LoginStatus.FAILED),

    OTP_INCORRECT("Verification code is incorrect", LoginStatus.OTP_FAILED),

    OTP_EXPIRED("Verification code has expired", LoginStatus.OTP_FAILED),

    OTP_NOT_ISSUED("No verification code is outstanding", LoginStatus.OTP_FAILED);

    private final String description;
    private final LoginStatus recordedAs;

    LoginOutcome(String description, LoginStatus recordedAs) {
        this.description = description;
        this.recordedAs = recordedAs;
    }

    public String getDescription() {
        return description;
    }

    /**
     * The status to write to {@code login_history}, or null while the attempt
     * is still in flight.
     */
    public LoginStatus getRecordedAs() {
        return recordedAs;
    }

    public boolean isAuthenticated() {
        return this == AUTHENTICATED;
    }

    /**
     * Whether the caller should go on to ask for a verification code.
     */
    public boolean needsOtp() {
        return this == OTP_REQUIRED;
    }

    public boolean isFailure() {
        return this != AUTHENTICATED && this != OTP_REQUIRED;
    }
}
