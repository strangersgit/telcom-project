package com.amdocs.telecom.service.impl;

import com.amdocs.telecom.dao.AuditLogDAO;
import com.amdocs.telecom.dao.CustomerDAO;
import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.LoginHistoryDAO;
import com.amdocs.telecom.dao.NetworkEngineerDAO;
import com.amdocs.telecom.dao.TransactionTemplate;
import com.amdocs.telecom.dao.UserDAO;
import com.amdocs.telecom.exception.AuthenticationException;
import com.amdocs.telecom.exception.DuplicateResourceException;
import com.amdocs.telecom.exception.ErrorCode;
import com.amdocs.telecom.exception.ValidationException;
import com.amdocs.telecom.model.AuditLog;
import com.amdocs.telecom.model.Customer;
import com.amdocs.telecom.model.LoginHistory;
import com.amdocs.telecom.model.NetworkEngineer;
import com.amdocs.telecom.model.UserAccount;
import com.amdocs.telecom.model.enums.AccountStatus;
import com.amdocs.telecom.model.enums.LoginStatus;
import com.amdocs.telecom.model.enums.Role;
import com.amdocs.telecom.security.AccessControl;
import com.amdocs.telecom.security.CaptchaChallenge;
import com.amdocs.telecom.security.CaptchaGenerator;
import com.amdocs.telecom.security.HashedPassword;
import com.amdocs.telecom.security.LoginOutcome;
import com.amdocs.telecom.security.LoginResult;
import com.amdocs.telecom.security.OtpService;
import com.amdocs.telecom.security.PasswordHasher;
import com.amdocs.telecom.security.PasswordPolicy;
import com.amdocs.telecom.security.Permission;
import com.amdocs.telecom.security.SessionContext;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.AuthenticationService;
import com.amdocs.telecom.util.AppConstants;
import com.amdocs.telecom.util.AppLogger;
import com.amdocs.telecom.util.ConsoleReader;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The login module of section 2.
 *
 * <p>Two principles run through the whole class. Nothing a caller can see
 * distinguishes an unknown username from a wrong password, because a prompt
 * that does is a way to enumerate accounts. And every completed attempt is
 * written to {@code login_history} before the answer is returned, so the
 * trail cannot be missing the attempt that mattered.</p>
 */
public final class AuthenticationServiceImpl implements AuthenticationService {

    private final UserDAO users;
    private final LoginHistoryDAO loginHistory;
    private final CustomerDAO customers;
    private final NetworkEngineerDAO engineers;
    private final AuditLogDAO auditLog;
    private final OtpService otpService;

    public AuthenticationServiceImpl() {
        this(DAOFactory.getInstance(), new OtpService());
    }

    public AuthenticationServiceImpl(DAOFactory factory, OtpService otpService) {
        this.users = factory.getUserDAO();
        this.loginHistory = factory.getLoginHistoryDAO();
        this.customers = factory.getCustomerDAO();
        this.engineers = factory.getNetworkEngineerDAO();
        this.auditLog = factory.getAuditLogDAO();
        this.otpService = otpService;
    }

    /* ---------- Step one: the challenge ---------- */

    @Override
    public CaptchaChallenge issueCaptcha() {
        return CaptchaGenerator.next();
    }

    /* ---------- Step two: CAPTCHA and password ---------- */

    @Override
    public LoginResult authenticate(String username, char[] password,
                                    CaptchaChallenge captcha, String captchaResponse) {
        try {
            return runAuthentication(username, password, captcha, captchaResponse);
        } finally {
            // The caller may forget; this class will not.
            ConsoleReader.clear(password);
        }
    }

    private LoginResult runAuthentication(String username, char[] password,
                                          CaptchaChallenge captcha, String captchaResponse) {
        String enteredName = username == null ? "" : username.trim();

        if (captcha == null || !captcha.verify(captchaResponse)) {
            record(enteredName, null, LoginOutcome.CAPTCHA_FAILED);
            return LoginResult.failure(LoginOutcome.CAPTCHA_FAILED,
                    "The CAPTCHA response was wrong or has expired. A new one will be shown.");
        }

        if (enteredName.isEmpty()) {
            record(enteredName, null, LoginOutcome.INVALID_CREDENTIALS);
            return LoginResult.invalidCredentials(-1);
        }

        Optional<UserAccount> found = users.findByUsername(enteredName);
        if (!found.isPresent()) {
            // Recorded against the typed name with no user id, which is why
            // login_history keeps a username column of its own.
            record(enteredName, null, LoginOutcome.INVALID_CREDENTIALS);
            AppLogger.warn(AuthenticationServiceImpl.class,
                    "Login attempt for unknown username '" + enteredName + "'");
            return LoginResult.invalidCredentials(-1);
        }

        UserAccount account = found.get();

        Optional<LoginResult> refusal = refuseIfUnusable(account);
        if (refusal.isPresent()) {
            return refusal.get();
        }

        if (!PasswordHasher.matches(password, account.getPasswordHash(), account.getPasswordSalt())) {
            return handleWrongPassword(account);
        }

        return issueOtpFor(account);
    }

    /**
     * Status checks that stop a login before the password is even compared.
     *
     * @return the refusal to return, or empty when the account may proceed
     */
    private Optional<LoginResult> refuseIfUnusable(UserAccount account) {
        if (account.getAccountStatus() == AccountStatus.DISABLED) {
            record(account, LoginOutcome.ACCOUNT_DISABLED);
            return Optional.of(LoginResult.failure(LoginOutcome.ACCOUNT_DISABLED,
                    "This account has been disabled. Contact the service desk."));
        }

        if (account.getAccountStatus() == AccountStatus.PENDING_ACTIVATION) {
            record(account, LoginOutcome.ACCOUNT_NOT_ACTIVATED);
            return Optional.of(LoginResult.failure(LoginOutcome.ACCOUNT_NOT_ACTIVATED,
                    "This account has not been activated yet. Contact the service desk."));
        }

        if (account.isCurrentlyLocked()) {
            record(account, LoginOutcome.ACCOUNT_LOCKED);
            return Optional.of(LoginResult.lockedOut(account.getLockedUntil()));
        }

        if (!account.hasUsablePassword()) {
            record(account, LoginOutcome.PASSWORD_NOT_SET);
            return Optional.of(LoginResult.failure(LoginOutcome.PASSWORD_NOT_SET,
                    "No password has been set for this account. Contact the service desk."));
        }

        return Optional.empty();
    }

    /**
     * Counts the failure and locks the account when it reaches the threshold.
     *
     * <p>A lock that has already expired leaves {@code account_status} saying
     * LOCKED until the next successful login clears it, so the count is read
     * back from the database after the increment rather than assumed.</p>
     */
    private LoginResult handleWrongPassword(UserAccount account) {
        Optional<UserAccount> updated = users.registerFailedAttempt(account.getId(),
                AppConstants.MAX_FAILED_LOGIN_ATTEMPTS, AppConstants.ACCOUNT_LOCK_MINUTES);

        UserAccount current = updated.orElse(account);
        int attemptsRemaining = Math.max(0,
                AppConstants.MAX_FAILED_LOGIN_ATTEMPTS - current.getFailedLoginAttempts());

        if (current.isCurrentlyLocked()) {
            record(current, LoginOutcome.ACCOUNT_LOCKED);
            AppLogger.warn(AuthenticationServiceImpl.class, "Account '" + current.getUsername()
                    + "' locked after " + current.getFailedLoginAttempts() + " failed attempts");
            return LoginResult.lockedOut(current.getLockedUntil());
        }

        record(current, LoginOutcome.INVALID_CREDENTIALS);
        return LoginResult.invalidCredentials(attemptsRemaining);
    }

    /**
     * Sends the second factor. Nothing is written to login_history yet: the
     * attempt is still in progress and will be recorded by its final step.
     */
    private LoginResult issueOtpFor(UserAccount account) {
        String destination = maskedDestinationFor(account);
        String delivery = otpService.issueAndRenderDelivery(account.getUsername(), destination);
        return LoginResult.otpRequired(delivery);
    }

    /* ---------- Step three: the one time password ---------- */

    @Override
    public LoginResult verifyOtp(String username, String otpCode) {
        String enteredName = username == null ? "" : username.trim();

        // Read the account again. It may have been locked or disabled by an
        // administrator in the seconds since the password was accepted.
        Optional<UserAccount> found = users.findByUsername(enteredName);
        if (!found.isPresent()) {
            otpService.discard(enteredName);
            return LoginResult.invalidCredentials(-1);
        }

        UserAccount account = found.get();
        Optional<LoginResult> refusal = refuseIfUnusable(account);
        if (refusal.isPresent()) {
            otpService.discard(enteredName);
            return refusal.get();
        }

        OtpService.OtpResult result = otpService.verify(enteredName, otpCode);
        if (!result.isAccepted()) {
            LoginOutcome outcome = outcomeFor(result);
            record(account, outcome);
            return LoginResult.failure(outcome, messageFor(result));
        }

        return completeLogin(account);
    }

    /**
     * Stamps the account, writes the success row and builds the session.
     *
     * <p>Wrapped in one transaction so a session can never exist without the
     * history row that records how it came about.</p>
     */
    private LoginResult completeLogin(UserAccount account) {
        // Whole seconds, so the account's last login and the history row hold
        // the same value the DATETIME columns will, and a later attempt never
        // sorts before this one.
        LocalDateTime signedInAt = LocalDateTime.now().withNano(0);

        UserSession session = TransactionTemplate.execute(context -> {
            users.recordSuccessfulLogin(account.getId(), signedInAt);

            LoginHistory entry = new LoginHistory(account.getId(), account.getUsername(),
                    LoginStatus.SUCCESS, null);
            entry.setLoginTime(signedInAt);
            LoginHistory saved = loginHistory.insert(entry);

            Long customerId = account.getRole() == Role.CUSTOMER
                    ? customers.findByUserId(account.getId()).map(Customer::getId).orElse(null)
                    : null;
            Long engineerId = account.getRole() == Role.NETWORK_ENGINEER
                    ? engineers.findByUserId(account.getId()).map(NetworkEngineer::getId).orElse(null)
                    : null;

            return new UserSession(account, saved.getId(), customerId, engineerId);
        });

        SessionContext.begin(session);
        AppLogger.info(AuthenticationServiceImpl.class, "Sign in: '" + account.getUsername()
                + "' as " + account.getRole());

        return LoginResult.authenticated(session, account.isMustChangePassword());
    }

    @Override
    public void abandonLogin(String username) {
        if (username != null) {
            otpService.discard(username.trim());
        }
    }

    /* ---------- Signing out ---------- */

    @Override
    public void logout(UserSession session) {
        if (session == null || session.isSignedOut()) {
            return;
        }
        session.getLoginHistoryId().ifPresent(
                historyId -> loginHistory.recordLogout(historyId, LocalDateTime.now()));

        session.markSignedOut();
        otpService.discard(session.getUsername());
        SessionContext.end();

        AppLogger.info(AuthenticationServiceImpl.class, "Sign out: '" + session.getUsername() + "'");
    }

    /* ---------- Passwords ---------- */

    @Override
    public void changeOwnPassword(UserSession session, char[] currentPassword, char[] newPassword) {
        try {
            if (session == null || !session.isActive()) {
                throw new AuthenticationException(ErrorCode.AUTH_SESSION_EXPIRED,
                        "You must be signed in to change your password.");
            }
            UserAccount account = users.getById(session.getUserId());

            if (!PasswordHasher.matches(currentPassword, account.getPasswordHash(),
                    account.getPasswordSalt())) {
                AppLogger.warn(AuthenticationServiceImpl.class,
                        "Password change refused for '" + account.getUsername()
                                + "': current password did not match");
                throw new AuthenticationException(ErrorCode.AUTH_INVALID_CREDENTIALS,
                        "The current password is incorrect.");
            }

            if (PasswordHasher.matches(newPassword, account.getPasswordHash(),
                    account.getPasswordSalt())) {
                throw new ValidationException("The new password must be different from the current one.");
            }

            applyNewPassword(account, newPassword, false,
                    "PASSWORD_CHANGED", account.getUsername());
            session.clearPasswordChangeRequired();

        } finally {
            ConsoleReader.clear(currentPassword);
            ConsoleReader.clear(newPassword);
        }
    }

    @Override
    public void resetPassword(UserSession administrator, String username, char[] temporaryPassword) {
        try {
            AccessControl.require(administrator, Permission.MANAGE_USERS);
            UserAccount account = requireAccount(username);

            // Flagged for change, so a password an administrator has seen
            // never stays in use.
            applyNewPassword(account, temporaryPassword, true,
                    "PASSWORD_RESET", administrator.getUsername());

        } finally {
            ConsoleReader.clear(temporaryPassword);
        }
    }

    /* ---------- Section 2's "Forgot Password" ---------- */

    @Override
    public String beginPasswordReset(String username) {
        String enteredName = username == null ? "" : username.trim();
        Optional<UserAccount> found = users.findByUsername(enteredName);

        // No code for an account that could not be signed into anyway. A
        // disabled account with a fresh password is still disabled, and
        // issuing one would only teach somebody that the name is real.
        if (!found.isPresent() || !canBeSignedInto(found.get())) {
            AppLogger.warn(AuthenticationServiceImpl.class,
                    "Password reset requested for an unknown or unusable account '"
                            + enteredName + "'");
            return "If that account exists and can be signed into, a code has been sent.";
        }

        UserAccount account = found.get();
        AppLogger.info(AuthenticationServiceImpl.class,
                "Password reset code issued for '" + account.getUsername() + "'");
        return otpService.issueAndRenderDelivery(account.getUsername(),
                maskedDestinationFor(account));
    }

    /**
     * The same state tests {@link #refuseIfUnusable} makes, without its
     * side effect.
     *
     * <p>That method records a failed login as it refuses, which is right
     * at the login prompt and wrong here: asking for a reset is not a
     * sign in attempt, and counting it as one would put an event in the
     * login history that never happened.</p>
     *
     * <p>A locked account is refused along with the rest. Some systems
     * allow a reset to clear a lockout, on the grounds that the person
     * locked out is usually the owner. This one does not, because the
     * lockout of section 3 is the defence against guessing and a route
     * around it would have to be at least as hard to pass as the guessing
     * it prevents. Waiting the lockout out, or asking the service desk,
     * are both still open.</p>
     */
    private boolean canBeSignedInto(UserAccount account) {
        return account.getAccountStatus() != AccountStatus.DISABLED
                && account.getAccountStatus() != AccountStatus.PENDING_ACTIVATION
                && !account.isCurrentlyLocked()
                && account.hasUsablePassword();
    }

    @Override
    public void completePasswordReset(String username, String otpCode, char[] newPassword) {
        String enteredName = username == null ? "" : username.trim();
        try {
            Optional<UserAccount> found = users.findByUsername(enteredName);

            // Re-checked rather than trusted from when the code was issued,
            // in case the account was disabled or locked in between. Both
            // cases give the refusal an incorrect code gets, so a caller
            // cannot tell them apart by working backwards from the message.
            if (!found.isPresent() || !canBeSignedInto(found.get())) {
                otpService.discard(enteredName);
                throw new AuthenticationException(ErrorCode.AUTH_INVALID_CREDENTIALS,
                        "That code is not valid. Start the reset again.");
            }

            UserAccount account = found.get();
            OtpService.OtpResult result = otpService.verify(enteredName, otpCode);
            if (!result.isAccepted()) {
                record(account, outcomeFor(result));
                throw new AuthenticationException(ErrorCode.AUTH_INVALID_CREDENTIALS,
                        messageFor(result));
            }

            // Not flagged for change: the user chose this one themselves, so
            // unlike an administrator's reset nobody else has ever seen it.
            applyNewPassword(account, newPassword, false,
                    "PASSWORD_RESET_SELF", account.getUsername());

        } finally {
            otpService.discard(enteredName);
            ConsoleReader.clear(newPassword);
        }
    }

    private void applyNewPassword(UserAccount account, char[] newPassword,
                                  boolean mustChangeNext, String action, String performedBy) {
        List<String> violations = PasswordPolicy.violations(newPassword, account.getUsername());
        if (!violations.isEmpty()) {
            throw new ValidationException("The password does not meet the policy.",
                    violations.stream().map(rule -> "Password " + rule).collect(Collectors.toList()));
        }

        HashedPassword hashed = PasswordHasher.hash(newPassword);

        TransactionTemplate.run(context -> {
            users.updatePassword(account.getId(), hashed.getHash(), hashed.getSalt(), mustChangeNext);
            auditLog.insert(AuditLog.forEntity(account, action, performedBy)
                    .withDetails("Credential re-derived using " + PasswordHasher.activeAlgorithm()));
        });

        AppLogger.info(AuthenticationServiceImpl.class,
                action + " for '" + account.getUsername() + "' by '" + performedBy + "'");
    }

    /* ---------- Account administration ---------- */

    @Override
    public boolean unlockAccount(UserSession administrator, String username) {
        AccessControl.require(administrator, Permission.MANAGE_USERS);
        UserAccount account = requireAccount(username);

        return TransactionTemplate.execute(context -> {
            boolean changed = users.unlock(account.getId());
            auditLog.insert(AuditLog.forEntity(account, "ACCOUNT_UNLOCKED",
                    administrator.getUsername())
                    .withChange(account.getAccountStatus().name(), AccountStatus.ACTIVE.name())
                    .withDetails("Failure count cleared from " + account.getFailedLoginAttempts()));
            return changed;
        });
    }

    @Override
    public boolean disableAccount(UserSession administrator, String username) {
        AccessControl.require(administrator, Permission.MANAGE_USERS);
        UserAccount account = requireAccount(username);

        if (administrator.getUsername().equalsIgnoreCase(account.getUsername())) {
            throw new ValidationException("You cannot disable the account you are signed in with.");
        }

        return TransactionTemplate.execute(context -> {
            boolean changed = users.updateStatus(account.getId(), AccountStatus.DISABLED, null);
            auditLog.insert(AuditLog.forEntity(account, "ACCOUNT_DISABLED",
                    administrator.getUsername())
                    .withChange(account.getAccountStatus().name(), AccountStatus.DISABLED.name()));
            return changed;
        });
    }

    @Override
    public UserAccount createAccount(UserSession administrator, String username, String fullName,
                                     String email, Role role, char[] initialPassword) {
        try {
            AccessControl.require(administrator, Permission.MANAGE_USERS);

            String trimmed = username == null ? "" : username.trim();
            if (trimmed.isEmpty() || fullName == null || email == null || role == null) {
                throw new ValidationException(
                        "Username, full name, email and role are all required.");
            }
            if (users.findByUsername(trimmed).isPresent()) {
                throw new DuplicateResourceException("Username '" + trimmed + "' is already taken.");
            }

            List<String> violations = PasswordPolicy.violations(initialPassword, trimmed);
            if (!violations.isEmpty()) {
                throw new ValidationException("The initial password does not meet the policy.",
                        violations.stream().map(rule -> "Password " + rule)
                                .collect(Collectors.toList()));
            }

            UserAccount account = new UserAccount(trimmed, fullName.trim(), email.trim(), role);
            HashedPassword hashed = PasswordHasher.hash(initialPassword);
            account.setPasswordHash(hashed.getHash());
            account.setPasswordSalt(hashed.getSalt());
            account.setAccountStatus(AccountStatus.ACTIVE);
            account.setMustChangePassword(true);

            return TransactionTemplate.execute(context -> {
                UserAccount saved = users.insert(account);
                auditLog.insert(AuditLog.forEntity(saved, "ACCOUNT_CREATED",
                        administrator.getUsername())
                        .withDetails("Role " + role.name() + ", must change password at first login"));
                AppLogger.info(AuthenticationServiceImpl.class, "Account created: '"
                        + saved.getUsername() + "' as " + role + " by '"
                        + administrator.getUsername() + "'");
                return saved;
            });

        } finally {
            ConsoleReader.clear(initialPassword);
        }
    }

    /* ---------- History ---------- */

    @Override
    public List<LoginHistory> loginHistoryFor(String username, int limit) {
        return loginHistory.findByUsername(username, limit);
    }

    @Override
    public Optional<LoginHistory> previousSuccessfulLogin(UserSession session) {
        Long currentEntry = session.getLoginHistoryId().orElse(null);
        return loginHistory.findByUserId(session.getUserId(), 20).stream()
                .filter(LoginHistory::isSuccessful)
                .filter(entry -> currentEntry == null || !currentEntry.equals(entry.getId()))
                .findFirst();
    }

    /* ---------- Helpers ---------- */

    private UserAccount requireAccount(String username) {
        return users.findByUsername(username == null ? "" : username.trim())
                .orElseThrow(() -> new ValidationException(
                        "No account exists with the username '" + username + "'."));
    }

    /**
     * Writes the attempt to login_history. A failure here must not mask the
     * authentication result the caller is waiting for, so it is logged rather
     * than thrown.
     */
    private void record(String username, Long userId, LoginOutcome outcome) {
        LoginStatus status = outcome.getRecordedAs();
        if (status == null) {
            return;
        }
        try {
            LoginHistory entry = new LoginHistory(userId, username, status, outcome.getDescription());
            loginHistory.insert(entry);
        } catch (RuntimeException failure) {
            AppLogger.error(AuthenticationServiceImpl.class,
                    "Could not write login history for '" + username + "'", failure);
        }
    }

    private void record(UserAccount account, LoginOutcome outcome) {
        record(account.getUsername(), account.getId(), outcome);
    }

    private static LoginOutcome outcomeFor(OtpService.OtpResult result) {
        switch (result) {
            case EXPIRED:
                return LoginOutcome.OTP_EXPIRED;
            case NOT_ISSUED:
                return LoginOutcome.OTP_NOT_ISSUED;
            case ATTEMPTS_EXHAUSTED:
            case INCORRECT:
            default:
                return LoginOutcome.OTP_INCORRECT;
        }
    }

    private static String messageFor(OtpService.OtpResult result) {
        switch (result) {
            case EXPIRED:
                return "That code has expired. Start the sign in again to get a new one.";
            case NOT_ISSUED:
                return "There is no code outstanding for this account. Start the sign in again.";
            case ATTEMPTS_EXHAUSTED:
                return "Too many incorrect codes. Start the sign in again to get a new one.";
            case INCORRECT:
            default:
                return "That code is incorrect.";
        }
    }

    /**
     * Where the code would really be sent, with most of it hidden. Shown so
     * the user can tell which of their numbers to look at without the whole
     * number appearing on a shared screen.
     */
    private String maskedDestinationFor(UserAccount account) {
        Optional<String> mobile = Optional.empty();
        if (account.getRole() == Role.CUSTOMER) {
            mobile = customers.findByUserId(account.getId()).map(Customer::getMobileNumber);
        } else if (account.getRole() == Role.NETWORK_ENGINEER) {
            mobile = engineers.findByUserId(account.getId()).map(NetworkEngineer::getMobileNumber);
        }
        return mobile.map(AuthenticationServiceImpl::maskMobile)
                .orElseGet(() -> maskEmail(account.getEmail()));
    }

    private static String maskMobile(String mobile) {
        if (mobile == null || mobile.length() < 4) {
            return "registered mobile";
        }
        int visible = 4;
        StringBuilder masked = new StringBuilder();
        for (int index = 0; index < mobile.length() - visible; index++) {
            masked.append('*');
        }
        masked.append(mobile.substring(mobile.length() - visible));
        return masked.toString();
    }

    private static String maskEmail(String email) {
        if (email == null) {
            return "registered email";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "registered email";
        }
        return email.charAt(0) + "***" + email.substring(at - 1);
    }
}
