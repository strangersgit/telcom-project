package com.amdocs.telecom.security;

import com.amdocs.telecom.util.AppConstants;

import java.time.LocalDateTime;

/**
 * One CAPTCHA challenge: the text to be read back, and the moment it stops
 * being accepted.
 *
 * <p>A challenge is answered once. {@link #verify(String)} marks it used, so
 * a captured screen cannot be replayed against the same challenge.</p>
 */
public final class CaptchaChallenge {

    private final String text;
    private final LocalDateTime issuedAt;
    private final LocalDateTime expiresAt;
    private boolean answered;

    CaptchaChallenge(String text, int validitySeconds) {
        this.text = text;
        this.issuedAt = LocalDateTime.now();
        this.expiresAt = this.issuedAt.plusSeconds(validitySeconds);
    }

    /**
     * The expected answer. Package private: the login flow compares through
     * {@link #verify(String)} rather than reading the answer out.
     */
    String getText() {
        return text;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isAnswered() {
        return answered;
    }

    /**
     * Checks a response, consuming the challenge either way.
     *
     * <p>Comparison ignores case and surrounding spaces: the point is to prove
     * a human read the distorted text, and insisting on exact case only
     * punishes the honest.</p>
     *
     * @return true when the response matches and the challenge was still live
     */
    public boolean verify(String response) {
        if (answered || isExpired() || response == null) {
            answered = true;
            return false;
        }
        answered = true;
        return text.equalsIgnoreCase(response.trim());
    }

    /**
     * The challenge drawn for a terminal.
     *
     * <p>A console cannot show a distorted image, so the characters are
     * spaced apart and framed. It keeps the step honest in the flow without
     * pretending to be image recognition.</p>
     */
    public String render() {
        StringBuilder spaced = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            if (index > 0) {
                spaced.append(' ');
            }
            spaced.append(text.charAt(index));
        }

        String border = frame(spaced.length() + 8);
        StringBuilder drawing = new StringBuilder();
        drawing.append("  ").append(border).append(System.lineSeparator());
        drawing.append("  |   ").append(spaced).append("   |").append(System.lineSeparator());
        drawing.append("  ").append(border);
        return drawing.toString();
    }

    private static String frame(int width) {
        char[] line = new char[width];
        java.util.Arrays.fill(line, '-');
        line[0] = '+';
        line[width - 1] = '+';
        return new String(line);
    }

    @Override
    public String toString() {
        return "CaptchaChallenge[length=" + AppConstants.CAPTCHA_LENGTH
                + ", expiresAt=" + expiresAt + ", answered=" + answered + "]";
    }
}
