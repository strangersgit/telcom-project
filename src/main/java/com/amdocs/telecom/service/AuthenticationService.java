package com.amdocs.telecom.service;

import com.amdocs.telecom.model.LoginHistory;
import com.amdocs.telecom.model.UserAccount;
import com.amdocs.telecom.model.enums.Role;
import com.amdocs.telecom.security.CaptchaChallenge;
import com.amdocs.telecom.security.LoginResult;
import com.amdocs.telecom.security.UserSession;

import java.util.List;
import java.util.Optional;

/**
 * The login module described in section 2 of the case study.
 *
 * <p>Signing in is three steps, in this order. A CAPTCHA proves a person is
 * at the keyboard, the password proves they know the secret, and a one time
 * password proves they hold the registered device. Each step is a separate
 * call so the console can prompt between them, and so the whole flow can be
 * exercised without a keyboard.</p>
 *
 * <pre>
 *   CaptchaChallenge challenge = service.issueCaptcha();
 *   LoginResult first = service.authenticate(username, password, challenge, typed);
 *   if (first.needsOtp()) {
 *       LoginResult second = service.verifyOtp(username, code);
 *   }
 * </pre>
 *
 * <p>Every completed attempt, successful or not, leaves a row in
 * {@code login_history}. Three failures lock the account for a quarter of an
 * hour.</p>
 */
public interface AuthenticationService {

    /* ---------- Signing in ---------- */

    /**
     * Issues the challenge to be shown before the credentials are asked for.
     */
    CaptchaChallenge issueCaptcha();

    /**
     * Checks the CAPTCHA and the password, and on success sends a one time
     * password.
     *
     * <p>The password array is cleared before this method returns, whatever
     * the outcome, so the caller does not have to remember to.</p>
     *
     * @param captcha  the challenge previously issued, which is consumed here
     * @param captchaResponse what the user typed for it
     * @return {@code OTP_REQUIRED} when the caller should go on to
     *         {@link #verifyOtp(String, String)}, otherwise a failure
     */
    LoginResult authenticate(String username, char[] password,
                             CaptchaChallenge captcha, String captchaResponse);

    /**
     * Checks the one time password and, if it is right, creates the session.
     *
     * <p>The account is read again here rather than trusted from the earlier
     * step, so an account locked or disabled in between cannot still
     * complete a login.</p>
     */
    LoginResult verifyOtp(String username, String otpCode);

    /**
     * Abandons a login part way through, discarding any outstanding code.
     */
    void abandonLogin(String username);

    /* ---------- Signing out ---------- */

    /**
     * Ends a session and stamps the logout time on its history row.
     */
    void logout(UserSession session);

    /* ---------- Passwords ---------- */

    /**
     * Changes one's own password, which requires proving the current one.
     *
     * @throws com.amdocs.telecom.exception.AuthenticationException when the
     *         current password is wrong
     * @throws com.amdocs.telecom.exception.ValidationException when the new
     *         password does not satisfy the policy
     */
    void changeOwnPassword(UserSession session, char[] currentPassword, char[] newPassword);

    /**
     * Sets a password on another account, used to provision a new user or to
     * help one who is locked out.
     *
     * <p>The account is flagged to require a change at next login, so an
     * administrator never knows a password that stays in use.</p>
     */
    void resetPassword(UserSession administrator, String username, char[] temporaryPassword);

    /* ---------- Section 2's "Forgot Password" ---------- */

    /**
     * Starts a self-service reset by issuing a one time code.
     *
     * <p>Reuses the second factor of the login rather than inventing a
     * separate token: the code, its lifetime and its attempt limit are
     * already defined, and a reset that were easier to complete than a
     * login would be the way in rather than the way back.</p>
     *
     * <p><b>Account existence is observable here.</b> The system has no SMS
     * or mail gateway, so the code is rendered to the screen as it is at
     * login, and a screen showing a code is a screen confirming the
     * account. Given a real delivery channel this would return the same
     * sentence either way and say nothing about whether anybody was
     * listening.</p>
     *
     * @return what to show the user: the code and where it would have been
     *         sent, or a neutral line when no code was issued
     */
    String beginPasswordReset(String username);

    /**
     * Finishes a self-service reset.
     *
     * <p>The new password is the user's own choice, so unlike
     * {@link #resetPassword} it is not flagged for immediate change:
     * nobody else has seen it.</p>
     *
     * @throws com.amdocs.telecom.exception.AuthenticationException when the
     *         code is wrong, expired or was never issued
     * @throws com.amdocs.telecom.exception.ValidationException when the new
     *         password does not satisfy the policy
     */
    void completePasswordReset(String username, String otpCode, char[] newPassword);

    /* ---------- Account administration ---------- */

    /**
     * Clears a lock and its failure count ahead of the timeout.
     */
    boolean unlockAccount(UserSession administrator, String username);

    boolean disableAccount(UserSession administrator, String username);

    /**
     * Creates a login account for a new user.
     */
    UserAccount createAccount(UserSession administrator, String username, String fullName,
                              String email, Role role, char[] initialPassword);

    /* ---------- History ---------- */

    /**
     * Recent attempts against one username, newest first.
     */
    List<LoginHistory> loginHistoryFor(String username, int limit);

    /**
     * The most recent successful sign in before the current one, which the
     * dashboard shows so a user can spot activity that was not theirs.
     */
    Optional<LoginHistory> previousSuccessfulLogin(UserSession session);
}
