package com.amdocs.telecom.util;

import java.time.format.DateTimeFormatter;

/**
 * Fixed values shared across the application.
 *
 * <p>Anything an operator might reasonably want to change lives in
 * {@code application.properties} instead; this class holds only the constants
 * that are part of the design.</p>
 */
public final class AppConstants {

    private AppConstants() {
        throw new AssertionError("AppConstants is not instantiable");
    }

    /* ---------- Date and time ---------- */

    /** Matches the sample ticket layout in the case study: 08-Aug-2026 18:30. */
    public static final DateTimeFormatter DISPLAY_DATE_TIME =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm");

    public static final DateTimeFormatter DISPLAY_DATE =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    public static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    public static final DateTimeFormatter LOG_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /* ---------- Identifier formats ---------- */

    /** Ticket numbers read TT-2026-004521. */
    public static final String TICKET_NUMBER_PREFIX = "TT";
    public static final String TICKET_NUMBER_FORMAT = "%s-%d-%06d";

    /** Customer numbers read CUST100245. */
    public static final String CUSTOMER_NUMBER_PREFIX = "CUST";

    /** Employee codes read ENG1008. */
    public static final String ENGINEER_CODE_PREFIX = "ENG";

    /** Network event identifiers read NE-884521. */
    public static final String EVENT_ID_PREFIX = "NE";

    /* ---------- Security ---------- */

    public static final int MAX_FAILED_LOGIN_ATTEMPTS = 3;
    public static final int ACCOUNT_LOCK_MINUTES = 15;
    public static final int CAPTCHA_LENGTH = 6;

    /** How long a CAPTCHA stays answerable. Long enough to type, short enough to matter. */
    public static final int CAPTCHA_VALIDITY_SECONDS = 120;

    public static final int OTP_LENGTH = 6;
    public static final int OTP_VALIDITY_MINUTES = 5;

    /** Wrong guesses allowed against one OTP before it is discarded. */
    public static final int MAX_OTP_ATTEMPTS = 3;
    public static final int MIN_PASSWORD_LENGTH = 8;
    public static final int PASSWORD_SALT_BYTES = 16;
    public static final int PASSWORD_HASH_ITERATIONS = 10000;

    /** Idle minutes after which a signed in session stops being usable. */
    public static final int SESSION_IDLE_MINUTES = 30;

    /**
     * Written by the seed script in place of a hash. An account still
     * holding it has never had a real password set and cannot log in.
     */
    public static final String PENDING_PASSWORD_SENTINEL = "PENDING_PHASE5";

    /* ---------- SLA ---------- */

    /**
     * Fraction of the resolution window that must elapse before a ticket is
     * reported as AT_RISK rather than WITHIN_SLA.
     */
    public static final double SLA_AT_RISK_THRESHOLD = 0.80d;

    /* ---------- Concurrency ---------- */

    public static final int EVENT_QUEUE_CAPACITY = 500;
    public static final int NOTIFICATION_QUEUE_CAPACITY = 500;
    public static final int EVENT_CONSUMER_THREADS = 2;
    public static final long SLA_MONITOR_INTERVAL_SECONDS = 30L;
    public static final long NOTIFICATION_POLL_INTERVAL_SECONDS = 15L;
    public static final long SHUTDOWN_GRACE_SECONDS = 10L;

    /* ---------- Console layout ---------- */

    public static final int CONSOLE_WIDTH = 68;
    public static final String LINE_DOUBLE = repeat('=', CONSOLE_WIDTH);
    public static final String LINE_SINGLE = repeat('-', CONSOLE_WIDTH);

    /* ---------- Validation patterns ---------- */

    public static final String EMAIL_PATTERN =
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";
    public static final String MOBILE_PATTERN = "^[6-9][0-9]{9}$";

    /**
     * Java 8 has no {@code String.repeat}, so the separators are built here.
     */
    private static String repeat(char character, int times) {
        char[] buffer = new char[times];
        java.util.Arrays.fill(buffer, character);
        return new String(buffer);
    }
}
