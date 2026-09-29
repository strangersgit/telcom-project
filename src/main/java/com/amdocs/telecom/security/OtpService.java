package com.amdocs.telecom.security;

import com.amdocs.telecom.util.AppConstants;
import com.amdocs.telecom.util.AppLogger;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues and checks the one time passwords that complete a login.
 *
 * <p>Codes live in memory only, keyed by username, and one account can hold
 * one live code at a time: issuing a new one abandons the old. Nothing is
 * written to the database, so a restart invalidates every outstanding code,
 * which is the behaviour a short lived second factor should have.</p>
 *
 * <p>There is no SMS gateway in a console application, so delivery is
 * simulated by printing the code where a message would have arrived. The
 * rendering lives here rather than in the caller because this is the only
 * place allowed to read a code back out.</p>
 */
public final class OtpService {

    private final Map<String, OneTimePassword> outstanding = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** How the check ended, so the caller can log and explain it precisely. */
    public enum OtpResult {

        ACCEPTED("Accepted"),
        INCORRECT("Code did not match"),
        EXPIRED("Code has expired"),
        ATTEMPTS_EXHAUSTED("Too many incorrect attempts"),
        NOT_ISSUED("No code was issued for this account");

        private final String description;

        OtpResult(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }

        public boolean isAccepted() {
            return this == ACCEPTED;
        }
    }

    /**
     * Issues a fresh code, replacing any the account already held.
     */
    public OneTimePassword issue(String username) {
        String code = String.format("%0" + AppConstants.OTP_LENGTH + "d",
                random.nextInt(power10(AppConstants.OTP_LENGTH)));

        OneTimePassword password =
                new OneTimePassword(code, username, AppConstants.OTP_VALIDITY_MINUTES);
        outstanding.put(username, password);

        // The code is never logged. Only the fact that one was issued.
        AppLogger.info(OtpService.class, "One time password issued for '" + username
                + "', valid for " + AppConstants.OTP_VALIDITY_MINUTES + " minutes");
        return password;
    }

    /**
     * Issues a code and renders the message it would have arrived in.
     *
     * @param destination where the code would really be sent, already masked
     * @return a block of text ready to print on the console
     */
    public String issueAndRenderDelivery(String username, String destination) {
        OneTimePassword password = issue(username);

        StringBuilder message = new StringBuilder();
        message.append("  ").append(AppConstants.LINE_SINGLE).append(System.lineSeparator());
        message.append("  SIMULATED SMS to ").append(destination == null ? "registered mobile" : destination)
                .append(System.lineSeparator());
        message.append("    Your TSATMS verification code is ").append(password.getCode())
                .append(System.lineSeparator());
        message.append("    It expires in ").append(AppConstants.OTP_VALIDITY_MINUTES)
                .append(" minutes. Do not share it with anyone.").append(System.lineSeparator());
        message.append("  ").append(AppConstants.LINE_SINGLE);
        return message.toString();
    }

    /**
     * Checks a code against the one outstanding for the account.
     *
     * <p>A code that is accepted, expired or exhausted is removed, so the
     * same value can never be presented twice.</p>
     */
    public OtpResult verify(String username, String candidate) {
        OneTimePassword password = outstanding.get(username);
        if (password == null) {
            return OtpResult.NOT_ISSUED;
        }

        if (password.isExpired()) {
            outstanding.remove(username, password);
            AppLogger.warn(OtpService.class, "Expired one time password presented for '" + username + "'");
            return OtpResult.EXPIRED;
        }
        if (!password.isLive()) {
            outstanding.remove(username, password);
            return OtpResult.ATTEMPTS_EXHAUSTED;
        }

        if (password.verify(candidate)) {
            outstanding.remove(username, password);
            return OtpResult.ACCEPTED;
        }

        if (password.isConsumed()) {
            outstanding.remove(username, password);
            AppLogger.warn(OtpService.class,
                    "One time password for '" + username + "' discarded after too many attempts");
            return OtpResult.ATTEMPTS_EXHAUSTED;
        }
        return OtpResult.INCORRECT;
    }

    /**
     * The code outstanding for an account, if any. Exposes no digits: the
     * login prompt uses it only to report how long is left.
     */
    public Optional<OneTimePassword> outstandingFor(String username) {
        return Optional.ofNullable(outstanding.get(username));
    }

    /**
     * Abandons any code held for an account, used when a login is given up.
     */
    public void discard(String username) {
        outstanding.remove(username);
    }

    /**
     * Drops codes nobody came back for. Worth calling from the scheduler in
     * a long running session so the map cannot grow without bound.
     *
     * @return how many were removed
     */
    public int purgeExpired() {
        int before = outstanding.size();
        outstanding.values().removeIf(OneTimePassword::isExpired);
        return before - outstanding.size();
    }

    /** How many codes are currently outstanding, for the diagnostics. */
    public int outstandingCount() {
        return outstanding.size();
    }

    private static int power10(int digits) {
        int value = 1;
        for (int index = 0; index < digits; index++) {
            value *= 10;
        }
        return value;
    }
}
