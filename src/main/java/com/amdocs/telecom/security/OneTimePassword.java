package com.amdocs.telecom.security;

import com.amdocs.telecom.util.AppConstants;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * A one time password issued to a single account.
 *
 * <p>Three things retire it: the clock passing {@link #getExpiresAt()}, one
 * correct use, or {@value AppConstants#MAX_OTP_ATTEMPTS} wrong guesses. The
 * attempt limit is what stops a six digit code from being worked through by
 * brute force, since there are only a million of them.</p>
 */
public final class OneTimePassword {

    private final String code;
    private final String username;
    private final LocalDateTime issuedAt;
    private final LocalDateTime expiresAt;

    private int attemptsUsed;
    private boolean consumed;

    OneTimePassword(String code, String username, int validityMinutes) {
        this.code = code;
        this.username = username;
        this.issuedAt = LocalDateTime.now();
        this.expiresAt = this.issuedAt.plusMinutes(validityMinutes);
    }

    public String getUsername() {
        return username;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public int getAttemptsUsed() {
        return attemptsUsed;
    }

    public int getAttemptsRemaining() {
        return Math.max(0, AppConstants.MAX_OTP_ATTEMPTS - attemptsUsed);
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isConsumed() {
        return consumed;
    }

    /**
     * Whether this code can still be presented for verification.
     */
    public boolean isLive() {
        return !consumed && !isExpired() && getAttemptsRemaining() > 0;
    }

    /**
     * Seconds left before expiry, floored at zero, for the console prompt.
     */
    public long getSecondsRemaining() {
        long seconds = Duration.between(LocalDateTime.now(), expiresAt).getSeconds();
        return Math.max(0L, seconds);
    }

    /**
     * Checks a typed code.
     *
     * <p>A correct code consumes the password outright. A wrong one spends an
     * attempt, and the last attempt consumes it too, so a caller cannot keep
     * guessing past the limit.</p>
     */
    boolean verify(String candidate) {
        if (!isLive() || candidate == null) {
            return false;
        }
        attemptsUsed++;

        boolean correct = code.equals(candidate.trim());
        if (correct || getAttemptsRemaining() == 0) {
            consumed = true;
        }
        return correct;
    }

    /**
     * The code itself, which only the issuing service may read so it can be
     * delivered. Nothing else in the application needs it.
     */
    String getCode() {
        return code;
    }

    /**
     * Describes the code without disclosing it.
     */
    @Override
    public String toString() {
        return "OneTimePassword[username=" + username
                + ", expiresAt=" + expiresAt
                + ", attemptsUsed=" + attemptsUsed
                + ", consumed=" + consumed + "]";
    }
}
