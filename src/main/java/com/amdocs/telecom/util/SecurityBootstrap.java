package com.amdocs.telecom.util;

import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.TransactionTemplate;
import com.amdocs.telecom.dao.UserDAO;
import com.amdocs.telecom.exception.TSATMSException;
import com.amdocs.telecom.model.AuditLog;
import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.UserAccount;
import com.amdocs.telecom.model.enums.AccountStatus;
import com.amdocs.telecom.security.HashedPassword;
import com.amdocs.telecom.security.PasswordHasher;
import com.amdocs.telecom.security.PasswordPolicy;

import java.util.List;

/**
 * Gives the seeded accounts a real password.
 *
 * <p>The seed script writes the sentinel {@value AppConstants#PENDING_PASSWORD_SENTINEL}
 * into {@code password_hash} and leaves every account
 * {@code PENDING_ACTIVATION}, because a SQL script has no way to produce a
 * PBKDF2 hash. This turns those placeholders into real salted credentials and
 * activates the accounts.</p>
 *
 * <p>Only accounts still holding the sentinel are touched, so running it a
 * second time changes nothing and cannot reset a password somebody has since
 * chosen for themselves.</p>
 */
public final class SecurityBootstrap {

    private SecurityBootstrap() {
        throw new AssertionError("SecurityBootstrap is not instantiable");
    }

    /** Property holding the password to install on the demo accounts. */
    private static final String DEMO_PASSWORD_KEY = "security.demo.password";

    /**
     * Provisions every account still awaiting a password.
     *
     * @return 0 on success, 1 when nothing could be provisioned
     */
    public static int provisionSeededAccounts() {
        ConfigLoader config = ConfigLoader.getInstance();
        UserDAO users = DAOFactory.getInstance().getUserDAO();

        System.out.println("  Account provisioning");
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println();

        List<UserAccount> awaiting = users.findAwaitingPassword();
        if (awaiting.isEmpty()) {
            System.out.println("  Every account already has a password. Nothing to do.");
            System.out.println();
            return 0;
        }

        char[] password = readDemoPassword(config);
        try {
            // Checked once, before anything is written. A configured value
            // that fails the policy would otherwise install credentials the
            // change-password screen would then refuse to accept.
            List<String> violations = PasswordPolicy.violations(password, null);
            if (!violations.isEmpty()) {
                System.out.println("  The password in " + DEMO_PASSWORD_KEY + " is not acceptable:");
                for (String violation : violations) {
                    System.out.println("    - Password " + violation);
                }
                System.out.println();
                System.out.println("  " + PasswordPolicy.describeRules());
                System.out.println("  Edit application.properties and run this again.");
                System.out.println();
                return 1;
            }

            System.out.println("  Hashing with " + PasswordHasher.activeAlgorithm()
                    + ", " + AppConstants.PASSWORD_HASH_ITERATIONS + " iterations, "
                    + AppConstants.PASSWORD_SALT_BYTES + " byte salt per account.");
            System.out.println();

            int provisioned = provision(users, awaiting, password);

            System.out.println();
            System.out.println("  " + provisioned + " of " + awaiting.size()
                    + " account(s) provisioned and activated.");
            System.out.println();
            System.out.println("  The password is the value of " + DEMO_PASSWORD_KEY
                    + " in application.properties.");
            System.out.println("  It is not printed here on purpose. Read it from that file.");
            System.out.println();

            AppLogger.info(SecurityBootstrap.class,
                    "Provisioned " + provisioned + " seeded account(s)");
            return provisioned == awaiting.size() ? 0 : 1;

        } finally {
            ConsoleReader.clear(password);
        }
    }

    /**
     * Each account gets its own salt, so the shared demo password still
     * produces a different stored hash for every row. Anyone looking at the
     * table cannot tell that the passwords are the same.
     */
    private static int provision(UserDAO users, List<UserAccount> awaiting, char[] password) {
        System.out.println(String.format("    %-14s %-26s %-28s %s",
                "USERNAME", "NAME", "ROLE", "RESULT"));

        int provisioned = 0;
        for (UserAccount account : awaiting) {
            HashedPassword hashed = PasswordHasher.hash(password);
            boolean done = applyTo(users, account, hashed);
            if (done) {
                provisioned++;
            }
            System.out.println(String.format("    %-14s %-26s %-28s %s",
                    account.getUsername(),
                    Displayable.truncate(account.getName(), 26),
                    account.getRole().getDisplayName(),
                    done ? "activated" : "FAILED"));
        }
        return provisioned;
    }

    private static boolean applyTo(UserDAO users, UserAccount account, HashedPassword hashed) {
        try {
            return TransactionTemplate.execute(context -> {
                boolean stored = users.updatePassword(account.getId(),
                        hashed.getHash(), hashed.getSalt(), false);
                boolean activated = users.updateStatus(account.getId(), AccountStatus.ACTIVE, null);

                DAOFactory.getInstance().getAuditLogDAO().insert(
                        AuditLog.forEntity(account, "ACCOUNT_PROVISIONED", "SYSTEM")
                                .withChange(AccountStatus.PENDING_ACTIVATION.name(),
                                        AccountStatus.ACTIVE.name())
                                .withDetails("Initial credential derived using "
                                        + PasswordHasher.activeAlgorithm()));

                return stored && activated;
            });
        } catch (TSATMSException failure) {
            AppLogger.error(SecurityBootstrap.class,
                    "Could not provision '" + account.getUsername() + "'", failure);
            return false;
        }
    }

    private static char[] readDemoPassword(ConfigLoader config) {
        return config.getString(DEMO_PASSWORD_KEY, "").toCharArray();
    }
}
