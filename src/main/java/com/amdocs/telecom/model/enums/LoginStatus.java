package com.amdocs.telecom.model.enums;

/**
 * Outcome of a login attempt, recorded in the login history.
 *
 * <p>The failure cases are kept distinct rather than collapsed into one, so
 * the security report can separate a wrong password from a failed CAPTCHA or
 * an expired one time password.</p>
 */
public enum LoginStatus implements DescribableEnum {

    SUCCESS("LS1", "Success"),
    FAILED("LS2", "Failed"),
    LOCKED("LS3", "Account Locked"),
    OTP_FAILED("LS4", "OTP Failed"),
    CAPTCHA_FAILED("LS5", "CAPTCHA Failed");

    private final String code;
    private final String displayName;

    LoginStatus(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Whether this outcome counts towards the failed attempt threshold that
     * locks an account.
     */
    public boolean countsAsFailedAttempt() {
        return this == FAILED || this == OTP_FAILED;
    }
}
