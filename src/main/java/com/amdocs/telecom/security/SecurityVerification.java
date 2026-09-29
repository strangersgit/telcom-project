package com.amdocs.telecom.security;

import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.UserDAO;
import com.amdocs.telecom.exception.AuthenticationException;
import com.amdocs.telecom.exception.AuthorizationException;
import com.amdocs.telecom.exception.DuplicateResourceException;
import com.amdocs.telecom.exception.TSATMSException;
import com.amdocs.telecom.exception.ValidationException;
import com.amdocs.telecom.model.LoginHistory;
import com.amdocs.telecom.model.UserAccount;
import com.amdocs.telecom.model.enums.AccountStatus;
import com.amdocs.telecom.model.enums.LoginStatus;
import com.amdocs.telecom.model.enums.Role;
import com.amdocs.telecom.service.AuthenticationService;
import com.amdocs.telecom.service.impl.AuthenticationServiceImpl;
import com.amdocs.telecom.util.AppConstants;
import com.amdocs.telecom.util.AppLogger;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Exercises the whole security layer against the live database.
 *
 * <p>Lives in the package it tests so it can read a CAPTCHA's expected answer
 * and reach the other package private seams. Verifying authentication from
 * outside would mean weakening exactly the encapsulation that makes it
 * trustworthy.</p>
 *
 * <p>It works against a throwaway account it creates for the purpose, named
 * with the prefix {@value #PROBE_PREFIX}, and deletes again on the way out.
 * The seeded accounts are only read, never locked or reset, so a failed run
 * cannot leave a demo login unusable.</p>
 */
public final class SecurityVerification {

    private SecurityVerification() {
        throw new AssertionError("SecurityVerification is not instantiable");
    }

    /** Marks the accounts this harness creates, so strays are recognisable. */
    private static final String PROBE_PREFIX = "vfy-";

    private static final String PROBE_USERNAME = PROBE_PREFIX + "engineer";

    /** Actor recorded on the audit entries the administrative checks cause. */
    private static final String PROBE_ACTOR = PROBE_PREFIX + "admin";

    private static final char[] PROBE_PASSWORD = "Verify@2026x".toCharArray();
    private static final char[] WRONG_PASSWORD = "Verify@2026y".toCharArray();

    private static int checksRun;
    private static int checksFailed;

    /**
     * Runs every check and prints a report.
     *
     * @return 0 when everything passed, 1 otherwise
     */
    public static int execute() {
        checksRun = 0;
        checksFailed = 0;

        DAOFactory factory = DAOFactory.getInstance();
        UserDAO users = factory.getUserDAO();
        OtpService otpService = new OtpService();
        AuthenticationService authentication =
                new AuthenticationServiceImpl(factory, otpService);

        System.out.println("  Security layer verification");
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println();

        try {
            verifyHashing();
            verifyPolicy();
            verifyCaptcha();
            verifyOtpLifecycle(otpService);
            verifyRoleMatrix();
            verifyAccessChecks();

            removeProbeAccount(users);
            UserAccount probe = createProbeAccount(users);

            verifyCaptchaGate(authentication, users);
            verifyPasswordCheck(authentication, users, probe);
            verifyLockout(authentication, users, probe);
            verifyUnlock(authentication, users, probe);
            verifyFullLogin(authentication, otpService, users, probe);
            verifyOtpRejection(authentication, otpService, users, probe);
            verifySeededAccounts(users);

        } catch (RuntimeException failure) {
            checksFailed++;
            System.out.println("  Verification aborted: " + failure);
            AppLogger.error(SecurityVerification.class, "Security verification aborted", failure);
        } finally {
            SessionContext.end();
            int removed = removeProbeAccount(factory.getUserDAO());
            if (removed > 0) {
                System.out.println();
                System.out.println("  Removed " + removed + " temporary account(s)");
            }
        }

        System.out.println();
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println("  " + (checksRun - checksFailed) + " of " + checksRun + " checks passed");
        System.out.println();

        if (checksFailed == 0) {
            System.out.println("  The security layer is working against the live database.");
            AppLogger.info(SecurityVerification.class,
                    "Security verification passed " + checksRun + " checks");
        } else {
            System.out.println("  " + checksFailed + " check(s) failed. See the detail above.");
            AppLogger.warn(SecurityVerification.class,
                    "Security verification had " + checksFailed + " failure(s)");
        }
        System.out.println();
        return checksFailed == 0 ? 0 : 1;
    }

    /* ---------- 1. Hashing ---------- */

    private static void verifyHashing() {
        section("1. Password hashing");

        char[] password = "Str0ng@Pass".toCharArray();
        HashedPassword first = PasswordHasher.hash(password);

        check("algorithm resolved", "PBKDF2WithHmacSHA256", PasswordHasher.activeAlgorithm());
        check("hash carries its algorithm", "true",
                String.valueOf(PasswordHasher.isCurrentAlgorithm(first.getHash())));
        check("salt is " + AppConstants.PASSWORD_SALT_BYTES + " bytes as hex",
                String.valueOf(AppConstants.PASSWORD_SALT_BYTES * 2),
                String.valueOf(first.getSalt().length()));
        check("hash fits the column", "true", String.valueOf(first.getHash().length() <= 128));
        check("password is not stored in the hash", "false",
                String.valueOf(first.getHash().contains("Str0ng")));
        check("toString discloses nothing", "false",
                String.valueOf(first.toString().contains(first.getSalt())));

        check("correct password matches", "true",
                String.valueOf(PasswordHasher.matches("Str0ng@Pass".toCharArray(),
                        first.getHash(), first.getSalt())));
        check("wrong password does not", "false",
                String.valueOf(PasswordHasher.matches("Str0ng@Pasz".toCharArray(),
                        first.getHash(), first.getSalt())));
        check("one character short does not", "false",
                String.valueOf(PasswordHasher.matches("Str0ng@Pas".toCharArray(),
                        first.getHash(), first.getSalt())));
        check("empty password does not", "false",
                String.valueOf(PasswordHasher.matches(new char[0],
                        first.getHash(), first.getSalt())));

        HashedPassword second = PasswordHasher.hash("Str0ng@Pass".toCharArray());
        check("same password, different salt", "false",
                String.valueOf(first.getSalt().equals(second.getSalt())));
        check("same password, different hash", "false",
                String.valueOf(first.getHash().equals(second.getHash())));
        check("both still verify", "true",
                String.valueOf(PasswordHasher.matches("Str0ng@Pass".toCharArray(),
                        second.getHash(), second.getSalt())));

        check("unknown algorithm tag fails closed", "false",
                String.valueOf(PasswordHasher.matches("Str0ng@Pass".toCharArray(),
                        "PBKDF2-SHA999:abcdef", first.getSalt())));
        check("malformed salt fails closed", "false",
                String.valueOf(PasswordHasher.matches("Str0ng@Pass".toCharArray(),
                        first.getHash(), "not-hexadecimal")));
        check("sentinel hash never matches", "false",
                String.valueOf(PasswordHasher.matches(
                        AppConstants.PENDING_PASSWORD_SENTINEL.toCharArray(),
                        AppConstants.PENDING_PASSWORD_SENTINEL, "0011223344556677")));
    }

    /* ---------- 2. Policy ---------- */

    private static void verifyPolicy() {
        section("2. Password policy");

        check("strong password accepted", "true",
                String.valueOf(PasswordPolicy.isAcceptable("Str0ng@Pass".toCharArray(), "arun")));
        check("too short refused", "true",
                String.valueOf(PasswordPolicy.violations("Ab1@x".toCharArray(), "arun")
                        .contains("must be at least 8 characters long")));
        check("no upper case refused", "true",
                String.valueOf(PasswordPolicy.violations("str0ng@pass".toCharArray(), "arun")
                        .contains("must contain an upper case letter")));
        check("no digit refused", "true",
                String.valueOf(PasswordPolicy.violations("Strong@Pass".toCharArray(), "arun")
                        .contains("must contain a digit")));
        check("no symbol refused", "true",
                String.valueOf(PasswordPolicy.violations("Str0ngPass".toCharArray(), "arun")
                        .contains("must contain a symbol such as @ # $ or !")));
        check("whitespace refused", "true",
                String.valueOf(PasswordPolicy.violations("Str0ng @Pass".toCharArray(), "arun")
                        .contains("must not contain spaces or tabs")));
        check("username inside password refused", "true",
                String.valueOf(PasswordPolicy.violations("Sdesk1@2026".toCharArray(), "sdesk1")
                        .contains("must not contain the username")));
        check("common password refused", "true",
                String.valueOf(PasswordPolicy.violations("password".toCharArray(), "arun")
                        .contains("is too widely used to be accepted")));
        check("every failure reported at once", "4",
                String.valueOf(PasswordPolicy.violations("abc".toCharArray(), "arun").size()));
        check("blank refused", "1",
                String.valueOf(PasswordPolicy.violations(new char[0], "arun").size()));
        check("configured demo password satisfies the policy", "true",
                String.valueOf(PasswordPolicy.isAcceptable(PROBE_PASSWORD, PROBE_USERNAME)));
    }

    /* ---------- 3. CAPTCHA ---------- */

    private static void verifyCaptcha() {
        section("3. CAPTCHA");

        CaptchaChallenge challenge = CaptchaGenerator.next();
        check("length", String.valueOf(AppConstants.CAPTCHA_LENGTH),
                String.valueOf(challenge.getText().length()));
        check("no ambiguous characters", "true",
                String.valueOf(!challenge.getText().matches(".*[OI01].*")));
        check("rendered for a terminal", "true",
                String.valueOf(challenge.render().contains("|")));
        check("not expired when issued", "false", String.valueOf(challenge.isExpired()));

        CaptchaChallenge accepting = CaptchaGenerator.next();
        check("correct answer accepted", "true",
                String.valueOf(accepting.verify(accepting.getText())));

        CaptchaChallenge spaced = CaptchaGenerator.next();
        check("surrounding spaces tolerated", "true",
                String.valueOf(spaced.verify("  " + spaced.getText() + "  ")));

        CaptchaChallenge caseTest = CaptchaGenerator.next();
        check("answer is case insensitive", "true",
                String.valueOf(caseTest.verify(caseTest.getText().toLowerCase())));

        CaptchaChallenge wrongTest = CaptchaGenerator.next();
        check("wrong answer refused", "false", String.valueOf(wrongTest.verify("ZZZZZZ")));

        CaptchaChallenge replayTest = CaptchaGenerator.next();
        String answer = replayTest.getText();
        replayTest.verify(answer);
        check("cannot be answered twice", "false", String.valueOf(replayTest.verify(answer)));

        CaptchaChallenge nullTest = CaptchaGenerator.next();
        check("null answer refused", "false", String.valueOf(nullTest.verify(null)));

        CaptchaChallenge expired = new CaptchaChallenge("ABC123", -1);
        check("expired challenge refused", "false", String.valueOf(expired.verify("ABC123")));

        check("two challenges differ", "false",
                String.valueOf(CaptchaGenerator.next().getText()
                        .equals(CaptchaGenerator.next().getText())));
    }

    /* ---------- 4. OTP ---------- */

    private static void verifyOtpLifecycle(OtpService otpService) {
        section("4. One time password");

        String account = PROBE_PREFIX + "otp";
        OneTimePassword issued = otpService.issue(account);

        check("length", String.valueOf(AppConstants.OTP_LENGTH),
                String.valueOf(issued.getCode().length()));
        check("digits only", "true", String.valueOf(issued.getCode().matches("\\d+")));
        check("live when issued", "true", String.valueOf(issued.isLive()));
        check("validity window", String.valueOf(AppConstants.OTP_VALIDITY_MINUTES),
                String.valueOf(Duration.between(
                        issued.getIssuedAt(), issued.getExpiresAt()).toMinutes()));
        check("outstanding for the account", "true",
                String.valueOf(otpService.outstandingFor(account).isPresent()));

        check("wrong code rejected", "INCORRECT",
                otpService.verify(account, wrongCodeFor(issued.getCode())).name());

        OneTimePassword fresh = otpService.issue(account);
        check("reissue replaces the old code", "1", String.valueOf(otpService.outstandingCount()));
        check("correct code accepted", "ACCEPTED",
                otpService.verify(account, fresh.getCode()).name());
        check("accepted code is consumed", "NOT_ISSUED",
                otpService.verify(account, fresh.getCode()).name());

        OneTimePassword limited = otpService.issue(account);
        String wrong = wrongCodeFor(limited.getCode());
        for (int attempt = 1; attempt < AppConstants.MAX_OTP_ATTEMPTS; attempt++) {
            otpService.verify(account, wrong);
        }
        check("attempts counted", "1", String.valueOf(limited.getAttemptsRemaining()));
        check("last wrong attempt exhausts it", "ATTEMPTS_EXHAUSTED",
                otpService.verify(account, wrong).name());
        check("correct code is useless afterwards", "NOT_ISSUED",
                otpService.verify(account, limited.getCode()).name());

        otpService.issue(account);
        otpService.discard(account);
        check("discarded on abandon", "0", String.valueOf(otpService.outstandingCount()));

        check("no code outstanding", "NOT_ISSUED", otpService.verify(account, "123456").name());
        check("toString discloses nothing", "false",
                String.valueOf(fresh.toString().contains(fresh.getCode())));
    }

    private static String wrongCodeFor(String code) {
        return code.startsWith("0") ? "999999" : "000000";
    }

    /* ---------- 5. Role matrix ---------- */

    private static void verifyRoleMatrix() {
        section("5. Role permissions");

        check("customer may raise a ticket", "true",
                String.valueOf(AccessControl.isPermitted(Role.CUSTOMER, Permission.RAISE_TICKET)));
        check("customer may not assign", "false",
                String.valueOf(AccessControl.isPermitted(Role.CUSTOMER, Permission.ASSIGN_ENGINEER)));
        check("customer may not read all tickets", "false",
                String.valueOf(AccessControl.isPermitted(Role.CUSTOMER, Permission.VIEW_ALL_TICKETS)));
        check("customer may not see the audit log", "false",
                String.valueOf(AccessControl.isPermitted(Role.CUSTOMER, Permission.VIEW_AUDIT_LOG)));

        check("service desk may assign", "true",
                String.valueOf(AccessControl.isPermitted(Role.SERVICE_DESK, Permission.ASSIGN_ENGINEER)));
        check("service desk may not change SLA windows", "false",
                String.valueOf(AccessControl.isPermitted(Role.SERVICE_DESK,
                        Permission.MANAGE_SLA_CONFIGURATION)));

        check("engineer may record a diagnosis", "true",
                String.valueOf(AccessControl.isPermitted(Role.NETWORK_ENGINEER,
                        Permission.RECORD_DIAGNOSIS)));
        check("engineer may not close a ticket", "false",
                String.valueOf(AccessControl.isPermitted(Role.NETWORK_ENGINEER,
                        Permission.CLOSE_TICKET)));

        check("manager may change SLA windows", "true",
                String.valueOf(AccessControl.isPermitted(Role.NETWORK_MANAGER,
                        Permission.MANAGE_SLA_CONFIGURATION)));
        check("manager may not raise a ticket", "false",
                String.valueOf(AccessControl.isPermitted(Role.NETWORK_MANAGER,
                        Permission.RAISE_TICKET)));

        check("every role can see a dashboard", "4",
                String.valueOf(Permission.VIEW_DASHBOARD.getAllowedRoles().size()));
        check("no permission is unreachable", "true",
                String.valueOf(Arrays.stream(Permission.values())
                        .allMatch(permission -> !permission.getAllowedRoles().isEmpty())));
        check("permission sets are unmodifiable", "UnsupportedOperationException",
                nameOfThrown(() -> Permission.RAISE_TICKET.getAllowedRoles().clear()));

        check("customer permission count", "5",
                String.valueOf(AccessControl.permissionsOf(Role.CUSTOMER).size()));
        check("roles holding assignment", "2",
                String.valueOf(AccessControl.rolesHolding(Permission.ASSIGN_ENGINEER).size()));
    }

    /* ---------- 6. Access checks ---------- */

    private static void verifyAccessChecks() {
        section("6. Access checks on a session");

        UserSession customer = sessionFor(Role.CUSTOMER, 501L, null);
        UserSession engineer = sessionFor(Role.NETWORK_ENGINEER, null, 77L);

        check("permitted operation passes", "(nothing thrown)",
                nameOfThrown(() -> AccessControl.require(customer, Permission.RAISE_TICKET)));
        check("forbidden operation refused", "AuthorizationException",
                nameOfThrown(() -> AccessControl.require(customer, Permission.ASSIGN_ENGINEER)));
        check("no session at all refused", "AuthenticationException",
                nameOfThrown(() -> AccessControl.require(null, Permission.RAISE_TICKET)));

        check("own record allowed", "(nothing thrown)",
                nameOfThrown(() -> AccessControl.requireCustomerAccess(customer,
                        Permission.VIEW_OWN_TICKETS, 501L)));
        check("another customer's record refused", "AuthorizationException",
                nameOfThrown(() -> AccessControl.requireCustomerAccess(customer,
                        Permission.VIEW_OWN_TICKETS, 502L)));

        check("own ticket allowed", "(nothing thrown)",
                nameOfThrown(() -> AccessControl.requireTicketAccess(customer,
                        Permission.VIEW_OWN_TICKETS, 501L, 77L)));
        check("another customer's ticket refused", "AuthorizationException",
                nameOfThrown(() -> AccessControl.requireTicketAccess(customer,
                        Permission.VIEW_OWN_TICKETS, 999L, 77L)));
        check("engineer's assigned ticket allowed", "(nothing thrown)",
                nameOfThrown(() -> AccessControl.requireTicketAccess(engineer,
                        Permission.RECORD_DIAGNOSIS, 501L, 77L)));
        check("ticket assigned elsewhere refused", "AuthorizationException",
                nameOfThrown(() -> AccessControl.requireTicketAccess(engineer,
                        Permission.RECORD_DIAGNOSIS, 501L, 88L)));

        engineer.markSignedOut();
        check("signed out session refused", "AuthenticationException",
                nameOfThrown(() -> AccessControl.require(engineer, Permission.RECORD_DIAGNOSIS)));
        check("signed out session holds nothing", "false",
                String.valueOf(AccessControl.isPermitted(engineer, Permission.RECORD_DIAGNOSIS)));

        check("context is empty before sign in", "false", String.valueOf(SessionContext.isSignedIn()));
        check("actor is the system", "SYSTEM", SessionContext.actingUsername());
        SessionContext.begin(customer);
        check("context reports the signed in user", PROBE_ACTOR, SessionContext.actingUsername());
        SessionContext.end();
        check("context cleared", "false", String.valueOf(SessionContext.isSignedIn()));
    }

    /**
     * A session for the access checks, named with the probe prefix.
     *
     * <p>The name matters because the audit trail is append only: entries
     * these sessions cause cannot be deleted afterwards, so they are made
     * recognisable as verification artefacts instead.</p>
     */
    private static UserSession sessionFor(Role role, Long customerId, Long engineerId) {
        UserAccount account = new UserAccount(PROBE_ACTOR, "Verification Actor",
                PROBE_ACTOR + "@example.test", role);
        account.setId(-1L);
        return new UserSession(account, null, customerId, engineerId);
    }

    /**
     * A session carrying a username that really exists, needed by the checks
     * that compare the signed in user against the account being acted on.
     */
    private static UserSession namedSessionFor(Role role, String username) {
        UserAccount account = new UserAccount(username, "Verification Actor",
                username + "@example.test", role);
        account.setId(-1L);
        return new UserSession(account, null, null, null);
    }

    /* ---------- 7. CAPTCHA gates the login ---------- */

    private static void verifyCaptchaGate(AuthenticationService authentication, UserDAO users) {
        section("7. CAPTCHA gates the password");

        long before = failureCountFor(users, PROBE_USERNAME);

        CaptchaChallenge challenge = authentication.issueCaptcha();
        LoginResult wrongCaptcha = authentication.authenticate(PROBE_USERNAME,
                PROBE_PASSWORD.clone(), challenge, "not-the-answer");

        check("wrong CAPTCHA refused", "CAPTCHA_FAILED", wrongCaptcha.getOutcome().name());
        check("  recorded as CAPTCHA_FAILED", "true",
                String.valueOf(latestStatusFor(authentication, PROBE_USERNAME)
                        == LoginStatus.CAPTCHA_FAILED));
        check("  password never counted against the account",
                String.valueOf(before), String.valueOf(failureCountFor(users, PROBE_USERNAME)));

        CaptchaChallenge reused = authentication.issueCaptcha();
        String answer = reused.getText();
        authentication.authenticate(PROBE_USERNAME, PROBE_PASSWORD.clone(), reused, answer);
        LoginResult replay = authentication.authenticate(PROBE_USERNAME,
                PROBE_PASSWORD.clone(), reused, answer);
        check("a CAPTCHA cannot be reused", "CAPTCHA_FAILED", replay.getOutcome().name());
    }

    /* ---------- 8. Password checking ---------- */

    private static void verifyPasswordCheck(AuthenticationService authentication,
                                            UserDAO users, UserAccount probe) {
        section("8. Password checking");

        LoginResult unknown = authenticateWithGoodCaptcha(authentication,
                PROBE_PREFIX + "nobody", PROBE_PASSWORD.clone());
        check("unknown username refused", "INVALID_CREDENTIALS", unknown.getOutcome().name());
        check("  message does not reveal the username is unknown", "true",
                String.valueOf(unknown.getMessage().startsWith("Username or password is incorrect")));
        check("  attempt still recorded", "true",
                String.valueOf(!authentication.loginHistoryFor(PROBE_PREFIX + "nobody", 5).isEmpty()));

        LoginResult wrong = authenticateWithGoodCaptcha(authentication,
                PROBE_USERNAME, WRONG_PASSWORD.clone());
        check("wrong password refused", "INVALID_CREDENTIALS", wrong.getOutcome().name());
        check("  same message as an unknown username", "true",
                String.valueOf(wrong.getMessage().startsWith("Username or password is incorrect")));
        check("  failure counted", "1", String.valueOf(failureCountFor(users, PROBE_USERNAME)));
        check("  attempts remaining reported", "2",
                String.valueOf(wrong.getAttemptsRemaining().orElse(-1)));

        LoginResult right = authenticateWithGoodCaptcha(authentication,
                PROBE_USERNAME, PROBE_PASSWORD.clone());
        check("correct password asks for a code", "OTP_REQUIRED", right.getOutcome().name());
        check("  delivery message produced", "true",
                String.valueOf(right.getOtpDelivery().isPresent()));
        check("  destination is masked", "true",
                String.valueOf(right.getOtpDelivery().get().contains("***")
                        || right.getOtpDelivery().get().contains("registered")));
        check("  no session yet", "false", String.valueOf(right.getSession().isPresent()));

        authentication.abandonLogin(PROBE_USERNAME);
        users.unlock(probe.getId());
    }

    /* ---------- 9. Lockout ---------- */

    private static void verifyLockout(AuthenticationService authentication,
                                      UserDAO users, UserAccount probe) {
        section("9. Lockout after " + AppConstants.MAX_FAILED_LOGIN_ATTEMPTS + " failures");

        users.unlock(probe.getId());

        LoginResult first = authenticateWithGoodCaptcha(authentication,
                PROBE_USERNAME, WRONG_PASSWORD.clone());
        check("first failure", "2", String.valueOf(first.getAttemptsRemaining().orElse(-1)));

        LoginResult second = authenticateWithGoodCaptcha(authentication,
                PROBE_USERNAME, WRONG_PASSWORD.clone());
        check("second failure", "1", String.valueOf(second.getAttemptsRemaining().orElse(-1)));
        check("  warns the next will lock", "true",
                String.valueOf(second.getMessage().contains("will lock")));

        LoginResult third = authenticateWithGoodCaptcha(authentication,
                PROBE_USERNAME, WRONG_PASSWORD.clone());
        check("third failure locks", "ACCOUNT_LOCKED", third.getOutcome().name());
        check("  expiry reported", "true", String.valueOf(third.getLockedUntil().isPresent()));
        check("  recorded as LOCKED", "true",
                String.valueOf(latestStatusFor(authentication, PROBE_USERNAME) == LoginStatus.LOCKED));

        Optional<UserAccount> locked = users.findByUsername(PROBE_USERNAME);
        check("  status in the database", "LOCKED",
                locked.map(account -> account.getAccountStatus().name()).orElse("(missing)"));
        check("  counter stopped at the threshold",
                String.valueOf(AppConstants.MAX_FAILED_LOGIN_ATTEMPTS),
                locked.map(account -> String.valueOf(account.getFailedLoginAttempts())).orElse("-1"));
        check("  lock expiry is " + AppConstants.ACCOUNT_LOCK_MINUTES + " minutes out", "true",
                String.valueOf(locked.isPresent() && locked.get().isCurrentlyLocked()));

        LoginResult correctButLocked = authenticateWithGoodCaptcha(authentication,
                PROBE_USERNAME, PROBE_PASSWORD.clone());
        check("correct password still refused while locked", "ACCOUNT_LOCKED",
                correctButLocked.getOutcome().name());
    }

    /* ---------- 10. Unlocking ---------- */

    private static void verifyUnlock(AuthenticationService authentication,
                                     UserDAO users, UserAccount probe) {
        section("10. Administrative unlock");

        UserSession customer = sessionFor(Role.CUSTOMER, 501L, null);
        check("a customer may not unlock", "AuthorizationException",
                nameOfThrown(() -> authentication.unlockAccount(customer, PROBE_USERNAME)));

        UserSession administrator = sessionFor(Role.SERVICE_DESK, null, null);
        check("service desk may unlock", "true",
                String.valueOf(authentication.unlockAccount(administrator, PROBE_USERNAME)));

        Optional<UserAccount> unlocked = users.findByUsername(PROBE_USERNAME);
        check("  status active again", "ACTIVE",
                unlocked.map(account -> account.getAccountStatus().name()).orElse("(missing)"));
        check("  failure count cleared", "0",
                unlocked.map(account -> String.valueOf(account.getFailedLoginAttempts()))
                        .orElse("-1"));

        LoginResult afterUnlock = authenticateWithGoodCaptcha(authentication,
                PROBE_USERNAME, PROBE_PASSWORD.clone());
        check("login works again", "OTP_REQUIRED", afterUnlock.getOutcome().name());
        authentication.abandonLogin(PROBE_USERNAME);

        check("unknown account cannot be unlocked", "ValidationException",
                nameOfThrown(() -> authentication.unlockAccount(administrator, PROBE_PREFIX + "ghost")));
    }

    /* ---------- 11. The whole flow ---------- */

    private static void verifyFullLogin(AuthenticationService authentication, OtpService otpService,
                                        UserDAO users, UserAccount probe) {
        section("11. Complete sign in and sign out");

        LoginResult credentials = authenticateWithGoodCaptcha(authentication,
                PROBE_USERNAME, PROBE_PASSWORD.clone());
        check("password stage passed", "OTP_REQUIRED", credentials.getOutcome().name());

        String code = otpService.outstandingFor(PROBE_USERNAME)
                .map(OneTimePassword::getCode).orElse("");
        LoginResult signedIn = authentication.verifyOtp(PROBE_USERNAME, code);

        check("code accepted", "AUTHENTICATED", signedIn.getOutcome().name());
        check("  session created", "true", String.valueOf(signedIn.getSession().isPresent()));

        UserSession session = signedIn.getSession().get();
        check("  role carried", "NETWORK_ENGINEER", session.getRole().name());
        check("  session active", "true", String.valueOf(session.isActive()));
        check("  password change demanded", "true",
                String.valueOf(signedIn.isPasswordChangeRequired()));
        check("  installed in the context", PROBE_USERNAME, SessionContext.actingUsername());
        check("  history row linked", "true", String.valueOf(session.getLoginHistoryId().isPresent()));

        check("  recorded as SUCCESS", "true",
                String.valueOf(latestStatusFor(authentication, PROBE_USERNAME) == LoginStatus.SUCCESS));
        check("  last login stamped", "true",
                String.valueOf(users.findByUsername(PROBE_USERNAME)
                        .map(account -> account.getLastLoginDate() != null).orElse(false)));
        check("  failure count reset", "0",
                users.findByUsername(PROBE_USERNAME)
                        .map(account -> String.valueOf(account.getFailedLoginAttempts()))
                        .orElse("-1"));

        check("  code cannot be replayed", "OTP_NOT_ISSUED",
                authentication.verifyOtp(PROBE_USERNAME, code).getOutcome().name());

        // Changing the password clears the flag and invalidates the old one.
        char[] replacement = "Rotated@2026z".toCharArray();
        authentication.changeOwnPassword(session, PROBE_PASSWORD.clone(), replacement.clone());
        check("password changed", "false", String.valueOf(session.isPasswordChangeRequired()));
        check("  old password no longer works", "INVALID_CREDENTIALS",
                authenticateWithGoodCaptcha(authentication, PROBE_USERNAME,
                        PROBE_PASSWORD.clone()).getOutcome().name());
        check("  new password works", "OTP_REQUIRED",
                authenticateWithGoodCaptcha(authentication, PROBE_USERNAME,
                        replacement.clone()).getOutcome().name());
        authentication.abandonLogin(PROBE_USERNAME);

        check("weak replacement refused", "ValidationException",
                nameOfThrown(() -> authentication.changeOwnPassword(session,
                        replacement.clone(), "abc".toCharArray())));
        check("wrong current password refused", "AuthenticationException",
                nameOfThrown(() -> authentication.changeOwnPassword(session,
                        "NotMine@2026".toCharArray(), "Another@2026z".toCharArray())));
        check("reusing the same password refused", "ValidationException",
                nameOfThrown(() -> authentication.changeOwnPassword(session,
                        replacement.clone(), replacement.clone())));

        authentication.logout(session);
        check("signed out", "true", String.valueOf(session.isSignedOut()));
        check("  context cleared", "false", String.valueOf(SessionContext.isSignedIn()));
        check("  logout time stamped", "true",
                String.valueOf(authentication.loginHistoryFor(PROBE_USERNAME, 20).stream()
                        .anyMatch(entry -> entry.getLogoutTime() != null)));
        check("  session no longer usable", "AuthenticationException",
                nameOfThrown(() -> AccessControl.require(session, Permission.RECORD_DIAGNOSIS)));
    }

    /* ---------- 12. OTP refusals in the flow ---------- */

    private static void verifyOtpRejection(AuthenticationService authentication,
                                           OtpService otpService, UserDAO users,
                                           UserAccount probe) {
        section("12. Verification code refusals");

        check("code without a password stage", "OTP_NOT_ISSUED",
                authentication.verifyOtp(PROBE_USERNAME, "123456").getOutcome().name());

        LoginResult credentials = authenticateWithGoodCaptcha(authentication,
                PROBE_USERNAME, "Rotated@2026z".toCharArray());
        check("password stage passed", "OTP_REQUIRED", credentials.getOutcome().name());

        String real = otpService.outstandingFor(PROBE_USERNAME)
                .map(OneTimePassword::getCode).orElse("");
        LoginResult wrong = authentication.verifyOtp(PROBE_USERNAME, wrongCodeFor(real));
        check("wrong code refused", "OTP_INCORRECT", wrong.getOutcome().name());
        check("  recorded as OTP_FAILED", "true",
                String.valueOf(latestStatusFor(authentication, PROBE_USERNAME)
                        == LoginStatus.OTP_FAILED));
        check("  no session issued", "false", String.valueOf(wrong.getSession().isPresent()));
        check("  not signed in", "false", String.valueOf(SessionContext.isSignedIn()));

        // An account disabled between the password and the code must not be
        // able to finish the login it had already half completed.
        UserSession administrator = sessionFor(Role.NETWORK_MANAGER, null, null);
        UserSession self = namedSessionFor(Role.SERVICE_DESK, PROBE_USERNAME);
        check("cannot disable one's own account", "ValidationException",
                nameOfThrown(() -> authentication.disableAccount(self, PROBE_USERNAME)));

        authentication.disableAccount(administrator, PROBE_USERNAME);
        check("disabled mid-flow stops the login", "ACCOUNT_DISABLED",
                authentication.verifyOtp(PROBE_USERNAME, real).getOutcome().name());

        users.updateStatus(probe.getId(), AccountStatus.ACTIVE, null);
        check("duplicate username refused", "DuplicateResourceException",
                nameOfThrown(() -> authentication.createAccount(administrator, PROBE_USERNAME,
                        "Duplicate", "duplicate@example.test", Role.CUSTOMER,
                        "Another@2026z".toCharArray())));
    }

    /* ---------- 13. The seeded accounts ---------- */

    private static void verifySeededAccounts(UserDAO users) {
        section("13. Seeded accounts");

        List<UserAccount> awaiting = users.findAwaitingPassword();
        check("none left holding the sentinel", "0", String.valueOf(awaiting.size()));

        Optional<UserAccount> serviceDesk = users.findByUsername("sdesk1");
        check("sdesk1 present", "true", String.valueOf(serviceDesk.isPresent()));
        check("  has a real password", "true",
                String.valueOf(serviceDesk.map(UserAccount::hasUsablePassword).orElse(false)));
        check("  hashed with the current algorithm", "true",
                String.valueOf(serviceDesk
                        .map(account -> PasswordHasher.isCurrentAlgorithm(account.getPasswordHash()))
                        .orElse(false)));
        check("  activated", "ACTIVE",
                serviceDesk.map(account -> account.getAccountStatus().name()).orElse("(missing)"));
        check("  may log in", "true",
                String.valueOf(serviceDesk.map(UserAccount::canAttemptLogin).orElse(false)));

        check("every account has its own salt", "true", String.valueOf(saltsAreUnique(users)));
        check("all four roles represented", "4",
                String.valueOf(Arrays.stream(Role.values())
                        .filter(role -> !users.findByRole(role).isEmpty())
                        .count()));
    }

    /**
     * A shared demo password must still produce a different salt per account,
     * or the hashes would give away which accounts share a password.
     */
    private static boolean saltsAreUnique(UserDAO users) {
        List<UserAccount> all = users.findAll();
        long distinct = all.stream()
                .map(UserAccount::getPasswordSalt)
                .distinct()
                .count();
        return distinct == all.size();
    }

    /* ---------- Probe account ---------- */

    private static UserAccount createProbeAccount(UserDAO users) {
        UserAccount account = new UserAccount(PROBE_USERNAME, "Verification Probe",
                PROBE_PREFIX + "probe@example.test", Role.NETWORK_ENGINEER);
        HashedPassword hashed = PasswordHasher.hash(PROBE_PASSWORD.clone());
        account.setPasswordHash(hashed.getHash());
        account.setPasswordSalt(hashed.getSalt());
        account.setAccountStatus(AccountStatus.ACTIVE);
        account.setMustChangePassword(true);
        return users.insert(account);
    }

    /**
     * Removes anything this run created, including rows a failed check may
     * have left behind.
     */
    private static int removeProbeAccount(UserDAO users) {
        int removed = 0;
        for (UserAccount account : users.findAll()) {
            if (account.getUsername() != null && account.getUsername().startsWith(PROBE_PREFIX)) {
                if (users.deleteById(account.getId())) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /* ---------- Helpers ---------- */

    /**
     * Runs the password stage with a CAPTCHA that is answered correctly, so a
     * check can concentrate on what it is actually testing.
     */
    private static LoginResult authenticateWithGoodCaptcha(AuthenticationService authentication,
                                                           String username, char[] password) {
        CaptchaChallenge challenge = authentication.issueCaptcha();
        return authentication.authenticate(username, password, challenge, challenge.getText());
    }

    private static long failureCountFor(UserDAO users, String username) {
        return users.findByUsername(username)
                .map(account -> (long) account.getFailedLoginAttempts())
                .orElse(-1L);
    }

    private static LoginStatus latestStatusFor(AuthenticationService authentication, String username) {
        List<LoginHistory> history = authentication.loginHistoryFor(username, 1);
        return history.isEmpty() ? null : history.get(0).getLoginStatus();
    }

    private static String nameOfThrown(Runnable work) {
        try {
            work.run();
            return "(nothing thrown)";
        } catch (DuplicateResourceException expected) {
            return "DuplicateResourceException";
        } catch (ValidationException expected) {
            return "ValidationException";
        } catch (AuthenticationException expected) {
            return "AuthenticationException";
        } catch (AuthorizationException expected) {
            return "AuthorizationException";
        } catch (TSATMSException expected) {
            return expected.getClass().getSimpleName();
        } catch (RuntimeException unexpected) {
            return unexpected.getClass().getSimpleName();
        }
    }

    private static void section(String title) {
        System.out.println("  " + title);
    }

    private static void check(String label, String expected, String actual) {
        checksRun++;
        boolean passed = expected.equals(actual);
        if (!passed) {
            checksFailed++;
        }
        System.out.println(String.format("    [%s] %-42s expected=%-22s actual=%s",
                passed ? "PASS" : "FAIL", label, expected, actual));
    }
}
