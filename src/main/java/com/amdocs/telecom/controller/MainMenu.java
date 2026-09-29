package com.amdocs.telecom.controller;

import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.exception.TSATMSException;
import com.amdocs.telecom.model.enums.Role;
import com.amdocs.telecom.security.PasswordPolicy;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.AuthenticationService;
import com.amdocs.telecom.service.EngineerAssignmentService;
import com.amdocs.telecom.service.EscalationService;
import com.amdocs.telecom.service.NotificationService;
import com.amdocs.telecom.service.ReportService;
import com.amdocs.telecom.service.SlaService;
import com.amdocs.telecom.service.TicketService;
import com.amdocs.telecom.util.AppConstants;
import com.amdocs.telecom.util.AppLogger;
import com.amdocs.telecom.util.ConsoleReader;

import java.util.Arrays;
import java.util.Optional;

/**
 * Section 2's front screen: four doors, a way to recover a password, and
 * a way out.
 *
 * <h3>Why the role is chosen before the password</h3>
 *
 * <p>Because section 2 says so, and it is not merely cosmetic. An account
 * has exactly one role, so signing in through the wrong door is a
 * mistake the screen can catch and explain rather than discovering three
 * menus later that none of the options work. A manager who picks
 * "Customer Login" is told what their account actually is, signed back
 * out, and returned here.</p>
 *
 * <p>It is not a security control: the door does not grant the role, the
 * account carries it. Picking the right door with the wrong password
 * gets nowhere, and picking the wrong door with the right password gets
 * an explanation.</p>
 *
 * <h3>Wiring</h3>
 *
 * <p>Every service is built once here and handed to whichever dashboard
 * needs it. The dashboards take their dependencies as constructor
 * arguments rather than reaching for singletons, which is what lets the
 * verification harness run the same screens against whatever it likes.</p>
 */
public final class MainMenu {

    private final ConsoleReader console;
    private final AuthenticationService authentication;
    private final TicketService tickets;
    private final EngineerAssignmentService assignments;
    private final EscalationService escalations;
    private final SlaService sla;
    private final NotificationService notifications;
    private final ReportService reports;
    private final DAOFactory factory;

    public MainMenu(ConsoleReader console, AuthenticationService authentication,
                    TicketService tickets, EngineerAssignmentService assignments,
                    EscalationService escalations, SlaService sla,
                    NotificationService notifications, ReportService reports,
                    DAOFactory factory) {
        this.console = console;
        this.authentication = authentication;
        this.tickets = tickets;
        this.assignments = assignments;
        this.escalations = escalations;
        this.sla = sla;
        this.notifications = notifications;
        this.reports = reports;
        this.factory = factory;
    }

    /**
     * Shows the front screen until the user exits or the input runs out.
     */
    public void run() {
        Menu menu = Menu.titled("TELECOM SERVICE ASSURANCE SYSTEM")
                .option("Customer Login", () -> signIn(Role.CUSTOMER))
                .option("Service Desk Login", () -> signIn(Role.SERVICE_DESK))
                .option("Network Engineer Login", () -> signIn(Role.NETWORK_ENGINEER))
                .option("Network Manager Login", () -> signIn(Role.NETWORK_MANAGER))
                .option("Forgot Password", this::forgotPassword)
                .exit("Exit", this::farewell)
                .build();

        // No session at this point, so nothing is permission gated: the
        // four doors are open to anybody, and what lies behind them is not.
        menu.runUntilExit(console, null);
    }

    /* ---------- The four doors ---------- */

    private void signIn(Role expected) {
        LoginController login = new LoginController(authentication, console);

        Optional<UserSession> signedIn = login.signIn();
        if (!signedIn.isPresent()) {
            return;
        }

        UserSession session = signedIn.get();
        if (session.getRole() != expected) {
            console.println();
            console.println("  This is the " + expected.getDisplayName()
                    + " entrance, but '" + session.getUsername() + "' is a "
                    + session.getRole().getDisplayName() + " account.");
            console.println("  Choose option " + doorFor(session.getRole())
                    + " and sign in again.");
            AppLogger.warn(MainMenu.class, session.getUsername() + " (a "
                    + session.getRole() + ") signed in at the " + expected + " door");
            login.signOut(session);
            return;
        }

        try {
            dashboardFor(session).open();
        } finally {
            // Whatever happened inside, the session must not be left open:
            // the login history row needs its sign out time either way.
            if (!session.isSignedOut()) {
                login.signOut(session);
            }
        }
    }

    /**
     * The dashboard a role gets, which is the only place the four screens
     * of sections 13 to 15 are chosen between.
     *
     * <p>Visible to the package so the verification harness can assert the
     * routing without signing anybody in.</p>
     */
    Dashboard dashboardFor(UserSession session) {
        switch (session.getRole()) {
            case CUSTOMER:
                return new CustomerDashboard(session, console, tickets, notifications);
            case SERVICE_DESK:
                return new ServiceDeskDashboard(session, console, tickets, assignments,
                        escalations, sla, reports, factory);
            case NETWORK_ENGINEER:
                return new NetworkEngineerDashboard(session, console, tickets, escalations,
                        notifications);
            case NETWORK_MANAGER:
                return new NetworkManagerDashboard(session, console, sla, reports, factory);
            default:
                throw new IllegalStateException(
                        "No dashboard is wired for " + session.getRole());
        }
    }

    /** Which numbered option a role should have used. */
    static int doorFor(Role role) {
        return Arrays.asList(Role.CUSTOMER, Role.SERVICE_DESK, Role.NETWORK_ENGINEER,
                Role.NETWORK_MANAGER).indexOf(role) + 1;
    }

    /* ---------- 5. Forgot Password ---------- */

    private void forgotPassword() {
        console.println();
        console.println("  " + AppConstants.LINE_SINGLE);
        console.println("  Reset a forgotten password");
        console.println("  " + AppConstants.LINE_SINGLE);

        Optional<String> username = console.readLine("  Username (or blank to go back): ");
        if (!username.isPresent() || username.get().isEmpty()) {
            return;
        }

        console.println();
        console.println("  " + authentication.beginPasswordReset(username.get()));
        console.println();
        console.println("  " + PasswordPolicy.describeRules());
        console.println();

        Optional<String> code = console.readLine("  Verification code: ");
        if (!code.isPresent() || code.get().isEmpty()) {
            return;
        }
        Optional<char[]> replacement = console.readSecret("  New password: ");
        if (!replacement.isPresent()) {
            return;
        }
        Optional<char[]> confirmation = console.readSecret("  Confirm new password: ");
        if (!confirmation.isPresent()) {
            ConsoleReader.clear(replacement.get());
            return;
        }

        if (!Arrays.equals(replacement.get(), confirmation.get())) {
            ConsoleReader.clear(replacement.get());
            ConsoleReader.clear(confirmation.get());
            console.println();
            console.println("  The two passwords do not match. Nothing was changed.");
            return;
        }
        ConsoleReader.clear(confirmation.get());

        try {
            authentication.completePasswordReset(username.get(), code.get(),
                    replacement.get());
            console.println();
            console.println("  Password changed. Sign in with it now.");
        } catch (TSATMSException refused) {
            console.println();
            console.println("  " + refused.toDisplayString());
        }
        console.println();
    }

    /* ---------- 6. Exit ---------- */

    private void farewell() {
        console.println();
        console.println("  Goodbye.");
        console.println();
    }
}
