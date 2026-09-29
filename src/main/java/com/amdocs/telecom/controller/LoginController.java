package com.amdocs.telecom.controller;

import com.amdocs.telecom.exception.TSATMSException;
import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.LoginHistory;
import com.amdocs.telecom.security.CaptchaChallenge;
import com.amdocs.telecom.security.LoginOutcome;
import com.amdocs.telecom.security.LoginResult;
import com.amdocs.telecom.security.PasswordPolicy;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.AuthenticationService;
import com.amdocs.telecom.util.AppConstants;
import com.amdocs.telecom.util.ConsoleReader;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The sign in screen of section 2.
 *
 * <p>Owns the conversation and nothing else. Every decision about whether a
 * login may proceed belongs to {@link AuthenticationService}; this class
 * prompts, prints and passes answers along. That split is what lets the same
 * rules be exercised by the verification harness with no keyboard
 * involved.</p>
 */
public final class LoginController {

    private final AuthenticationService authentication;
    private final ConsoleReader console;

    public LoginController(AuthenticationService authentication, ConsoleReader console) {
        this.authentication = authentication;
        this.console = console;
    }

    /**
     * Runs the three steps until a session is produced or the user gives up.
     *
     * @return the session, or empty when the user chose to quit
     */
    public Optional<UserSession> signIn() {
        while (true) {
            printHeader();

            Optional<String> username = console.readLine(
                    "  Username (or blank to return): ");
            if (!username.isPresent() || username.get().isEmpty()) {
                return Optional.empty();
            }

            Optional<UserSession> session = attempt(username.get());
            if (session.isPresent()) {
                return session;
            }
            if (console.isInputExhausted()) {
                return Optional.empty();
            }

            Optional<Boolean> again = console.readYesNo("  Try again?");
            if (!again.isPresent() || !again.get()) {
                return Optional.empty();
            }
        }
    }

    /**
     * One complete attempt for one username.
     */
    private Optional<UserSession> attempt(String username) {
        CaptchaChallenge challenge = authentication.issueCaptcha();

        console.println();
        console.println("  Type the characters shown below.");
        console.println(challenge.render());
        console.println();

        Optional<String> captchaResponse = console.readLine("  CAPTCHA: ");
        if (!captchaResponse.isPresent()) {
            return Optional.empty();
        }

        if (!console.isEchoMaskingAvailable()) {
            console.println();
            console.println("  Note: this terminal cannot hide what you type,");
            console.println("  so the password will be visible on screen.");
        }

        Optional<char[]> password = console.readSecret("  Password: ");
        if (!password.isPresent()) {
            return Optional.empty();
        }

        LoginResult credentials = authentication.authenticate(
                username, password.get(), challenge, captchaResponse.get());

        if (!credentials.needsOtp()) {
            report(credentials);
            return Optional.empty();
        }

        return verifyOtp(username, credentials);
    }

    /**
     * The second factor. The user gets a few tries at the code before the
     * service discards it and the whole attempt restarts.
     */
    private Optional<UserSession> verifyOtp(String username, LoginResult credentials) {
        console.println();
        credentials.getOtpDelivery().ifPresent(console::println);
        console.println();

        for (int attempt = 1; attempt <= AppConstants.MAX_OTP_ATTEMPTS; attempt++) {
            Optional<String> code = console.readLine("  Verification code: ");
            if (!code.isPresent()) {
                authentication.abandonLogin(username);
                return Optional.empty();
            }

            LoginResult result = authentication.verifyOtp(username, code.get());
            if (result.isAuthenticated()) {
                return onSignedIn(result);
            }

            report(result);
            if (result.getOutcome() != LoginOutcome.OTP_INCORRECT) {
                return Optional.empty();
            }
        }

        authentication.abandonLogin(username);
        return Optional.empty();
    }

    /**
     * Shows what the session lands on, including the forced password change
     * when an administrator set the current one.
     */
    private Optional<UserSession> onSignedIn(LoginResult result) {
        UserSession session = result.getSession().get();

        console.println();
        console.println("  " + AppConstants.LINE_DOUBLE);
        console.println("  " + result.getMessage());
        console.println("  " + AppConstants.LINE_DOUBLE);

        authentication.previousSuccessfulLogin(session).ifPresent(previous ->
                console.println("  Your previous sign in was "
                        + Displayable.formatDateTime(previous.getLoginTime())
                        + ". If that was not you, change your password."));
        console.println();

        if (result.isPasswordChangeRequired() && !forcePasswordChange(session)) {
            authentication.logout(session);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /**
     * Refuses to go any further until the password is replaced.
     *
     * @return true when a new password was accepted
     */
    private boolean forcePasswordChange(UserSession session) {
        console.println("  This password was set for you and must be changed now.");
        console.println("  " + PasswordPolicy.describeRules());
        console.println();

        while (true) {
            if (!changePassword(session)) {
                if (console.isInputExhausted()) {
                    return false;
                }
                Optional<Boolean> retry = console.readYesNo("  Try a different password?");
                if (!retry.isPresent() || !retry.get()) {
                    console.println("  Signing out. The password still needs changing.");
                    return false;
                }
                continue;
            }
            return true;
        }
    }

    /**
     * Prompts for the current and new password and asks the service to make
     * the change. Also reachable from the menus later on.
     *
     * @return true when the change was accepted
     */
    public boolean changePassword(UserSession session) {
        Optional<char[]> current = console.readSecret("  Current password: ");
        if (!current.isPresent()) {
            return false;
        }
        Optional<char[]> replacement = console.readSecret("  New password: ");
        if (!replacement.isPresent()) {
            ConsoleReader.clear(current.get());
            return false;
        }
        Optional<char[]> confirmation = console.readSecret("  Confirm new password: ");
        if (!confirmation.isPresent()) {
            ConsoleReader.clear(current.get());
            ConsoleReader.clear(replacement.get());
            return false;
        }

        if (!Arrays.equals(replacement.get(), confirmation.get())) {
            ConsoleReader.clear(current.get());
            ConsoleReader.clear(replacement.get());
            ConsoleReader.clear(confirmation.get());
            console.println("  The two new passwords do not match.");
            return false;
        }
        ConsoleReader.clear(confirmation.get());

        try {
            authentication.changeOwnPassword(session, current.get(), replacement.get());
            console.println("  Password changed.");
            return true;
        } catch (TSATMSException refused) {
            console.println("  " + refused.toDisplayString());
            return false;
        }
    }

    /**
     * Signs out and stamps the history row.
     */
    public void signOut(UserSession session) {
        authentication.logout(session);
        console.println();
        console.println("  Signed out. Thank you for using TSATMS.");
        console.println();
    }

    /**
     * Recent attempts against one account, for the service desk screen.
     */
    public void showLoginHistory(String username, int limit) {
        List<LoginHistory> history = authentication.loginHistoryFor(username, limit);
        console.println();
        console.println("  Recent sign in attempts for '" + username + "'");
        console.println("  " + AppConstants.LINE_SINGLE);
        if (history.isEmpty()) {
            console.println("  Nothing recorded.");
        } else {
            console.println(String.format("  %-18s %-16s %-16s %-18s %s",
                    "WHEN", "USERNAME", "RESULT", "SIGNED OUT", "REASON"));
            for (LoginHistory entry : history) {
                console.println("  " + entry.toSummaryLine());
            }
        }
        console.println();
    }

    private void printHeader() {
        console.println();
        console.println("  " + AppConstants.LINE_DOUBLE);
        console.println("                    TSATMS  -  SIGN IN");
        console.println("  " + AppConstants.LINE_DOUBLE);
    }

    /**
     * Prints why an attempt did not succeed, using the sentence the service
     * produced rather than inventing one here.
     */
    private void report(LoginResult result) {
        console.println();
        console.println("  " + result.getMessage());
        result.getLockedUntil().ifPresent(until ->
                console.println("  Locked for " + AppConstants.ACCOUNT_LOCK_MINUTES
                        + " minutes from the last attempt."));
        console.println();
    }
}
