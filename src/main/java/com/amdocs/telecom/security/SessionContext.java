package com.amdocs.telecom.security;

import com.amdocs.telecom.exception.AuthenticationException;
import com.amdocs.telecom.exception.ErrorCode;

import java.util.Optional;

/**
 * Holds the session of whoever is currently signed in at the console.
 *
 * <p>The application is interactive and single seated: one person is at the
 * keyboard, so one reference is enough and passing a session through every
 * controller method would add noise without adding safety.</p>
 *
 * <p>The field is {@code volatile} because the background workers added later
 * read it to attribute their audit entries, and they run on other threads.
 * They never write it; only signing in and out does that.</p>
 */
public final class SessionContext {

    private SessionContext() {
        throw new AssertionError("SessionContext is not instantiable");
    }

    /** Recorded as the actor when work happens with nobody signed in. */
    public static final String SYSTEM_ACTOR = "SYSTEM";

    private static volatile UserSession current;

    /**
     * Installs the session produced by a successful login.
     */
    public static void begin(UserSession session) {
        current = session;
    }

    /**
     * Forgets the current session. The authentication service calls this
     * after it has stamped the logout time.
     */
    public static void end() {
        current = null;
    }

    /**
     * The signed in session, or empty when nobody is signed in or the session
     * has lapsed.
     */
    public static Optional<UserSession> current() {
        UserSession session = current;
        if (session == null || !session.isActive()) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /**
     * The signed in session, for code that cannot proceed without one.
     *
     * @throws AuthenticationException when nobody is signed in
     */
    public static UserSession require() {
        return current().orElseThrow(() -> new AuthenticationException(
                ErrorCode.AUTH_SESSION_EXPIRED, "You must sign in to continue"));
    }

    public static boolean isSignedIn() {
        return current().isPresent();
    }

    /**
     * Who to record against a change: the signed in username, or
     * {@value #SYSTEM_ACTOR} when a background worker is acting on its own.
     */
    public static String actingUsername() {
        return current().map(UserSession::getUsername).orElse(SYSTEM_ACTOR);
    }
}
