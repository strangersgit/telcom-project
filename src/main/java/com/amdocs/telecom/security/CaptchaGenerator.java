package com.amdocs.telecom.security;

import com.amdocs.telecom.util.AppConstants;

import java.security.SecureRandom;

/**
 * Issues the CAPTCHA challenge that section 2 puts in front of the password
 * prompt.
 *
 * <p>The alphabet leaves out characters that are hard to tell apart when read
 * back from a screen: no letter O against digit zero, no letter I or lower
 * case L against digit one. A challenge nobody can read reliably only trains
 * people to retry until they get an easy one.</p>
 */
public final class CaptchaGenerator {

    private CaptchaGenerator() {
        throw new AssertionError("CaptchaGenerator is not instantiable");
    }

    /** Upper case letters and digits, minus the pairs that look alike. */
    private static final char[] ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * A new challenge of the configured length.
     */
    public static CaptchaChallenge next() {
        return next(AppConstants.CAPTCHA_LENGTH);
    }

    public static CaptchaChallenge next(int length) {
        char[] characters = new char[Math.max(1, length)];
        for (int index = 0; index < characters.length; index++) {
            characters[index] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new CaptchaChallenge(new String(characters), AppConstants.CAPTCHA_VALIDITY_SECONDS);
    }
}
