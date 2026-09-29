package com.amdocs.telecom.controller;

import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.TransactionTemplate;
import com.amdocs.telecom.dao.TroubleTicketDAO;
import com.amdocs.telecom.exception.ValidationException;
import com.amdocs.telecom.model.Customer;
import com.amdocs.telecom.model.NetworkEngineer;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.UserAccount;
import com.amdocs.telecom.model.enums.AccountStatus;
import com.amdocs.telecom.model.enums.Role;
import com.amdocs.telecom.security.Permission;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.AuthenticationService;
import com.amdocs.telecom.service.impl.AuthenticationServiceImpl;
import com.amdocs.telecom.service.impl.EngineerAssignmentServiceImpl;
import com.amdocs.telecom.service.impl.EscalationServiceImpl;
import com.amdocs.telecom.service.impl.NotificationServiceImpl;
import com.amdocs.telecom.service.impl.ReportServiceImpl;
import com.amdocs.telecom.service.impl.SlaServiceImpl;
import com.amdocs.telecom.service.impl.TicketServiceImpl;
import com.amdocs.telecom.util.AppConstants;
import com.amdocs.telecom.util.AppLogger;
import com.amdocs.telecom.util.ConsoleReader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.sql.Savepoint;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drives the dashboards of sections 13 to 15 with scripted keystrokes.
 *
 * <h3>How a console screen is tested at all</h3>
 *
 * <p>Every read goes through {@link ConsoleReader}, and a
 * {@code ConsoleReader} can be pointed at any stream. So a screen can be
 * given a list of the keys a user would press and asked for the
 * transcript it would have printed, with nothing mocked and no code path
 * that exists only for the test: the harness runs exactly the menus the
 * application runs.</p>
 *
 * <p>The scripts allow for the pause after each option, since the menu
 * waits for Enter before scrolling on, and they end with blank lines
 * before the logout digit. A blank line at a menu prompt is rejected and
 * re-asked, so the padding is absorbed whether or not the screen above
 * asked its optional question, and the script does not have to predict
 * what the seeded data contains.</p>
 *
 * <h3>What it writes</h3>
 *
 * <p>All but one section is a read. The exception raises a ticket through
 * the customer's own screen, inside a transaction that is rolled back to
 * a savepoint, and then checks the ticket is gone. Anything the rollback
 * somehow missed is deleted by name at the end and reported.</p>
 */
public final class ConsoleVerification {

    private ConsoleVerification() {
        throw new AssertionError("ConsoleVerification is not instantiable");
    }

    /** Ticket numbers raised through the console, for the last resort cleanup. */
    private static final List<String> ISSUED = new ArrayList<String>();

    private static final Pattern TICKET_NUMBER = Pattern.compile("TT-\\d{4}-\\d{6}");

    private static final Pattern OTP_CODE =
            Pattern.compile("verification code is (\\d+)");

    /** Long enough that a touched timestamp is visibly later on Windows. */
    private static final long CLOCK_GAP_MILLIS = 60L;

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
        ISSUED.clear();

        DAOFactory factory = DAOFactory.getInstance();

        System.out.println("  Console dashboard verification");
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println();

        try {
            verifyOptions();
            verifyBuilder();
            verifyRendering();
            verifyDispatch();
            verifyLoopEnds();
            verifyRouting(factory);
            verifyCustomerScreen(factory);
            verifyEngineerScreen(factory);
            verifyServiceDeskScreen(factory);
            verifyManagerScreen(factory);
            verifyRaisingRollsBack(factory);
            verifyForgottenPassword(factory);
        } catch (RuntimeException failure) {
            checksFailed++;
            System.out.println("  Verification aborted: " + failure);
            AppLogger.error(ConsoleVerification.class, "Console verification aborted", failure);
        } finally {
            int removed = removeIssuedTickets(factory.getTroubleTicketDAO());
            if (removed > 0) {
                checksFailed++;
                System.out.println();
                System.out.println("  " + removed + " ticket(s) survived the rollback and "
                        + "had to be deleted.");
            }
        }

        System.out.println();
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println("  " + (checksRun - checksFailed) + " of " + checksRun
                + " checks passed");
        System.out.println();

        if (checksFailed == 0) {
            System.out.println("  Every dashboard runs from scripted input and leaves the "
                    + "database as it found it.");
            AppLogger.info(ConsoleVerification.class,
                    "Console verification passed " + checksRun + " checks");
        } else {
            System.out.println("  " + checksFailed + " check(s) failed. See the detail above.");
            AppLogger.warn(ConsoleVerification.class,
                    "Console verification had " + checksFailed + " failure(s)");
        }
        System.out.println();
        return checksFailed == 0 ? 0 : 1;
    }

    /* ---------- 1. A single option ---------- */

    private static void verifyOptions() {
        section("1. What one menu option knows about itself");

        MenuOption plain = MenuOption.of("Plain", () -> { });
        MenuOption guarded = MenuOption.guarded("Guarded", Permission.MANAGE_USERS, () -> { });
        MenuOption leave = MenuOption.exit("Logout");

        check("a label is kept as given", "Plain", plain.getLabel());
        check("  and trimmed", "Padded", MenuOption.of("  Padded  ", () -> { }).getLabel());
        check("a plain option guards nothing", "false",
                String.valueOf(plain.findRequiredPermission().isPresent()));
        check("a guarded one names its permission", "MANAGE_USERS",
                guarded.findRequiredPermission().map(Enum::name).orElse("(none)"));

        check("only the exit option ends the menu", "false", String.valueOf(plain.isTerminal()));
        check("  which the exit option does", "true", String.valueOf(leave.isTerminal()));

        UserSession customer = sessionFor(Role.CUSTOMER, null, null);
        UserSession manager = sessionFor(Role.NETWORK_MANAGER, null, null);

        check("a plain option is open to anyone", "true",
                String.valueOf(plain.isAvailableTo(customer)));
        check("  including nobody at all, as at the sign in screen", "true",
                String.valueOf(plain.isAvailableTo(null)));
        check("a guarded one is closed to a role without it", "false",
                String.valueOf(guarded.isAvailableTo(customer)));
        check("  and closed when there is no session", "false",
                String.valueOf(guarded.isAvailableTo(null)));
        check("  open to a role that holds it", "true", String.valueOf(
                MenuOption.guarded("Reports", Permission.VIEW_REPORTS, () -> { })
                        .isAvailableTo(manager)));

        check("the refusal names the permission rather than shrugging", "true",
                String.valueOf(guarded.describeRefusal()
                        .contains(Permission.MANAGE_USERS.getDescription().toLowerCase())));
        check("  and an ungated option has a refusal to fall back on",
                "That option is not available.", plain.describeRefusal());

        check("an option needs a label", "IllegalArgumentException",
                nameOfThrown(() -> MenuOption.of("   ", () -> { })));
        check("an option needs something to do", "IllegalArgumentException",
                nameOfThrown(() -> MenuOption.of("Empty", null)));
        check("a guarded option needs a permission", "IllegalArgumentException",
                nameOfThrown(() -> MenuOption.guarded("Loose", null, () -> { })));
    }

    /* ---------- 2. The builder ---------- */

    private static void verifyBuilder() {
        section("2. Building a menu");

        Menu menu = probeMenu(new int[1]);
        check("the title is kept", "Probe", menu.getTitle());
        check("every option was collected", "5", String.valueOf(menu.size()));
        check("  in the order they were declared",
                "First, Guarded, Throws, Refuses, Leave",
                labelsOf(menu));
        check("the option list cannot be changed from outside",
                "UnsupportedOperationException",
                nameOfThrown(() -> menu.getOptions().clear()));

        check("a menu needs a title", "IllegalArgumentException",
                nameOfThrown(() -> Menu.titled("  ")));
        check("a menu needs at least one option", "IllegalStateException",
                nameOfThrown(() -> Menu.titled("Bare").build()));
        check("a menu needs a way out", "IllegalStateException",
                nameOfThrown(() -> Menu.titled("Trap").option("Stay", () -> { }).build()));
        check("  which an exit option satisfies", "(nothing thrown)",
                nameOfThrown(() -> Menu.titled("Fine").exit("Leave").build()));
    }

    /* ---------- 3. Rendering ---------- */

    private static void verifyRendering() {
        section("3. How a menu appears on screen");

        Menu menu = probeMenu(new int[1]);
        String screen = menu.render(sessionFor(Role.CUSTOMER, null, null));
        String[] lines = screen.split("\\R");

        check("the title is on the screen", "true", String.valueOf(screen.contains("Probe")));
        check("every option is listed", "5", String.valueOf(countMatching(lines, "\\s+\\d+\\..*")));
        check("  numbered from one", "true",
                String.valueOf(lineContaining(lines, "First").trim().startsWith("1.")));
        check("  to the last", "true",
                String.valueOf(lineContaining(lines, "Leave").trim().startsWith("5.")));

        // The point of showing an unavailable option rather than dropping
        // it: the numbers a role sees are the numbers everybody sees.
        check("an option the role may not choose keeps its number", "true",
                String.valueOf(lineContaining(lines, "Guarded").trim().startsWith("2.")));
        check("  and says so", "true",
                String.valueOf(lineContaining(lines, "Guarded")
                        .contains("(not available to your role)")));
        check("an option the role may choose says nothing", "false",
                String.valueOf(lineContaining(lines, "First")
                        .contains("(not available to your role)")));

        String forNobody = menu.render(null);
        check("with no session the guarded option is closed too", "true",
                String.valueOf(lineContaining(forNobody.split("\\R"), "Guarded")
                        .contains("(not available to your role)")));
        check("  while the open ones stay open", "false",
                String.valueOf(lineContaining(forNobody.split("\\R"), "First")
                        .contains("(not available to your role)")));

        check("the list is ruled off above and below", "3",
                String.valueOf(countMatching(lines, "\\s+" + Pattern.quote(AppConstants.LINE_SINGLE))));
    }

    /* ---------- 4. Choosing an option ---------- */

    private static void verifyDispatch() {
        section("4. Choosing an option");

        UserSession customer = sessionFor(Role.CUSTOMER, null, null);

        int[] ran = new int[1];
        String transcript = drive(probeMenu(ran), customer, "1", "", "5");
        check("choosing an option runs it", "1", String.valueOf(ran[0]));
        check("  then waits before scrolling on", "true",
                String.valueOf(transcript.contains("Press Enter to continue")));

        ran[0] = 0;
        transcript = drive(probeMenu(ran), customer, "2", "5");
        check("choosing a forbidden option does not run it", "0", String.valueOf(ran[0]));
        check("  and says why", "true",
                String.valueOf(transcript.contains("Your role may not")));
        check("  without pausing, since nothing happened", "false",
                String.valueOf(transcript.contains("Press Enter to continue")));

        ran[0] = 0;
        transcript = drive(probeMenu(ran), customer, "3", "", "1", "", "5");
        check("an option that breaks does not end the session", "1", String.valueOf(ran[0]));
        check("  the failure is shown", "true",
                String.valueOf(transcript.contains("That did not work: deliberate")));
        check("  and the log is pointed at", "true",
                String.valueOf(transcript.contains("The details are in the log.")));

        ran[0] = 0;
        transcript = drive(probeMenu(ran), customer, "4", "", "1", "", "5");
        check("a business refusal is shown as the service worded it", "true",
                String.valueOf(transcript.contains("deliberate refusal")));
        check("  and the menu carries on", "1", String.valueOf(ran[0]));

        ran[0] = 0;
        transcript = drive(probeMenu(ran), customer, "x", "9", "0", "1", "", "5");
        check("something that is not a number is rejected", "true",
                String.valueOf(transcript.contains("'x' is not a number.")));
        check("a number off the end of the list is rejected", "true",
                String.valueOf(transcript.contains("Enter a number between 1 and 5.")));
        check("  and the menu is still usable afterwards", "1", String.valueOf(ran[0]));

        ran[0] = 0;
        drive(probeMenu(ran), customer, "5", "1", "");
        check("the exit option stops the menu at once", "0", String.valueOf(ran[0]));
    }

    /* ---------- 5. Ending the loop ---------- */

    private static void verifyLoopEnds() {
        section("5. When the menu gives up");

        UserSession customer = sessionFor(Role.CUSTOMER, null, null);

        int[] ran = new int[1];
        // No exit digit at all. A menu that re-prompted for ever would
        // hang here rather than fail, so reaching the next line is the
        // check.
        drive(probeMenu(ran), customer, "1", "");
        check("running out of input ends the menu", "1", String.valueOf(ran[0]));

        ran[0] = 0;
        drive(probeMenu(ran), customer);
        check("  even with no input at all", "0", String.valueOf(ran[0]));

        UserSession expired = sessionFor(Role.CUSTOMER, null, null);
        expired.markSignedOut();
        ran[0] = 0;
        String transcript = drive(probeMenu(ran), expired, "1", "", "5");
        check("a session that has ended reaches no option", "0", String.valueOf(ran[0]));
        check("  and is told to sign in again", "true",
                String.valueOf(transcript.contains("Your session has ended")));

        UserSession active = sessionFor(Role.CUSTOMER, null, null);
        LocalDateTime before = active.getLastActivity();
        sleep(CLOCK_GAP_MILLIS);
        drive(probeMenu(new int[1]), active, "1", "", "5");
        check("working on a dashboard pushes the idle timeout out", "true",
                String.valueOf(active.getLastActivity().isAfter(before)));
    }

    /* ---------- 6. Which dashboard a role gets ---------- */

    private static void verifyRouting(DAOFactory factory) {
        section("6. The door, the role and the dashboard");

        MainMenu main = mainMenu(factory, new ConsoleReader(
                new ByteArrayInputStream(new byte[0]), new PrintStream(
                        new ByteArrayOutputStream(), true)));

        check("a customer lands on the customer dashboard", "CustomerDashboard",
                main.dashboardFor(sessionFor(Role.CUSTOMER, 1L, null)).getClass().getSimpleName());
        check("the service desk on its own", "ServiceDeskDashboard",
                main.dashboardFor(sessionFor(Role.SERVICE_DESK, null, null))
                        .getClass().getSimpleName());
        check("an engineer on theirs", "NetworkEngineerDashboard",
                main.dashboardFor(sessionFor(Role.NETWORK_ENGINEER, null, 1L))
                        .getClass().getSimpleName());
        check("a manager on theirs", "NetworkManagerDashboard",
                main.dashboardFor(sessionFor(Role.NETWORK_MANAGER, null, null))
                        .getClass().getSimpleName());

        check("every role has a dashboard", "4", String.valueOf(Role.values().length));

        // Section 2 numbers the four doors in this order, and a mismatch
        // is reported by pointing at the right one.
        check("the customer door is option 1", "1", String.valueOf(MainMenu.doorFor(Role.CUSTOMER)));
        check("the service desk door is option 2", "2",
                String.valueOf(MainMenu.doorFor(Role.SERVICE_DESK)));
        check("the engineer door is option 3", "3",
                String.valueOf(MainMenu.doorFor(Role.NETWORK_ENGINEER)));
        check("the manager door is option 4", "4",
                String.valueOf(MainMenu.doorFor(Role.NETWORK_MANAGER)));
    }

    /* ---------- 7. The customer dashboard, live ---------- */

    private static void verifyCustomerScreen(DAOFactory factory) {
        section("7. Section 13's customer dashboard");

        Customer customer = busiestCustomer(factory);
        if (customer == null) {
            check("a seeded customer with tickets was found", "true", "false");
            return;
        }
        String ticketNumber = firstTicketOf(factory, customer.getId());
        UserSession session = sessionFor(Role.CUSTOMER, customer.getId(), null);

        Capture capture = new Capture("1", "",
                "3", "",
                "4", ticketNumber, "",
                "5", ticketNumber, "",
                "6", "n", "", "",
                "8");
        new CustomerDashboard(session, capture.console(), new TicketServiceImpl(),
                new NotificationServiceImpl()).open();
        String transcript = capture.transcript();

        check("the banner names the screen", "true",
                String.valueOf(transcript.contains("CUSTOMER DASHBOARD")));
        check("  and who is on it", "true",
                String.valueOf(transcript.contains(session.getUsername())));
        check("all eight options of section 13 are offered", "true",
                String.valueOf(transcript.contains("8. Logout")));
        check("  raising a ticket among them", "true",
                String.valueOf(transcript.contains("Raise Trouble Ticket")));
        check("  and none is closed to a customer", "false",
                String.valueOf(transcript.contains("(not available to your role)")));

        check("the services screen ran", "true",
                String.valueOf(transcript.contains("Your services")));
        check("the ticket list ran", "true",
                String.valueOf(transcript.contains("Your tickets")));
        check("  showing the columns section 13 asks for", "true",
                String.valueOf(transcript.contains("TICKET")
                        && transcript.contains("SERVICE")
                        && transcript.contains("PRIORITY")
                        && transcript.contains("STATUS")
                        && transcript.contains("ENGINEER")
                        && transcript.contains("SLA")));
        check("tracking showed the ticket", "true",
                String.valueOf(transcript.contains("Ticket " + ticketNumber)));
        check("the history screen ran", "true",
                String.valueOf(transcript.contains("History of " + ticketNumber)));
        check("the inbox ran", "true",
                String.valueOf(transcript.contains("Your notifications")));

        check("nothing failed unexpectedly", "true", String.valueOf(noFailures(transcript)));
        check("the screen was left cleanly", "false",
                String.valueOf(transcript.contains("Your session has ended")));
    }

    /* ---------- 8. The engineer dashboard, live ---------- */

    private static void verifyEngineerScreen(DAOFactory factory) {
        section("8. The network engineer's dashboard");

        NetworkEngineer engineer = busiestEngineer(factory);
        if (engineer == null) {
            check("a seeded engineer was found", "true", "false");
            return;
        }
        UserSession session = sessionFor(Role.NETWORK_ENGINEER, null, engineer.getId());

        Capture capture = new Capture("1", "",
                "3", "", "",
                "7", "n", "", "",
                "8");
        new NetworkEngineerDashboard(session, capture.console(), new TicketServiceImpl(),
                new EscalationServiceImpl(), new NotificationServiceImpl()).open();
        String transcript = capture.transcript();

        check("the queue screen ran", "true",
                String.valueOf(transcript.contains("Assigned to you")));
        check("section 10's four fields are reachable", "true",
                String.valueOf(transcript.contains("Record Diagnosis")
                        && transcript.contains("Resolve Ticket")));
        check("escalation is offered, as section 9 requires", "true",
                String.valueOf(transcript.contains("Escalate Ticket")));
        check("an engineer is not offered anyone else's work", "false",
                String.valueOf(transcript.contains("Assign Engineer")));
        check("backing out of a prompt changes nothing", "true",
                String.valueOf(transcript.contains("Nothing was changed.")));
        check("the inbox ran", "true",
                String.valueOf(transcript.contains("Your notifications")));
        check("nothing failed unexpectedly", "true", String.valueOf(noFailures(transcript)));
    }

    /* ---------- 9. The service desk dashboard, live ---------- */

    private static void verifyServiceDeskScreen(DAOFactory factory) {
        section("9. Section 14's service desk dashboard");

        UserSession session = sessionFor(Role.SERVICE_DESK, null, null);

        Capture capture = new Capture("1", "",
                "6", "",
                "2", "", "",
                "3", "", "",
                "9");
        new ServiceDeskDashboard(session, capture.console(), new TicketServiceImpl(),
                new EngineerAssignmentServiceImpl(), new EscalationServiceImpl(),
                new SlaServiceImpl(), new ReportServiceImpl(), factory).open();
        String transcript = capture.transcript();

        check("all eight options of section 14 are offered", "true",
                String.valueOf(transcript.contains("View Open Tickets")
                        && transcript.contains("Assign Engineer")
                        && transcript.contains("Reassign Ticket")
                        && transcript.contains("Escalate Ticket")
                        && transcript.contains("Update Priority")
                        && transcript.contains("Monitor SLA")
                        && transcript.contains("Close Ticket")
                        && transcript.contains("Generate Reports")));
        check("  with a ninth to leave by", "true",
                String.valueOf(transcript.contains("9. Logout")));
        check("  and none closed to the service desk", "false",
                String.valueOf(transcript.contains("(not available to your role)")));

        check("the open queue ran", "true",
                String.valueOf(transcript.contains("Open tickets")));
        check("the SLA monitor ran", "true",
                String.valueOf(transcript.contains("SLA compliance by band")));
        check("  reporting compliance per band", "true",
                String.valueOf(transcript.contains("COMPLIANCE")));
        check("the assignment screen ran", "true",
                String.valueOf(transcript.contains("Waiting for an engineer")
                        || transcript.contains("Everything open already has an engineer")));
        check("nothing failed unexpectedly", "true", String.valueOf(noFailures(transcript)));
    }

    /* ---------- 10. The manager dashboard, live ---------- */

    private static void verifyManagerScreen(DAOFactory factory) {
        section("10. Section 15's network manager dashboard");

        UserSession session = sessionFor(Role.NETWORK_MANAGER, null, null);

        Capture capture = new Capture("2", "",
                "3", "",
                "4", "",
                "5", "",
                "7", "",
                "8");
        new NetworkManagerDashboard(session, capture.console(), new SlaServiceImpl(),
                new ReportServiceImpl(), factory).open();
        String transcript = capture.transcript();

        // Section 15 prints these six before it asks anything.
        check("the operations summary appears on arrival", "true",
                String.valueOf(transcript.contains("Operations summary")));
        check("  Total Open Tickets", "true",
                String.valueOf(transcript.contains("Total Open Tickets")));
        check("  Critical Incidents", "true",
                String.valueOf(transcript.contains("Critical Incidents")));
        check("  SLA At Risk", "true", String.valueOf(transcript.contains("SLA At Risk")));
        check("  SLA Breached", "true", String.valueOf(transcript.contains("SLA Breached")));
        check("  Resolved Today", "true", String.valueOf(transcript.contains("Resolved Today")));
        check("  Average Resolution Time", "true",
                String.valueOf(transcript.contains("Average Resolution Time")));

        check("the open ticket list ran", "true",
                String.valueOf(transcript.contains("Everything still open")));
        check("compliance by band ran", "true",
                String.valueOf(transcript.contains("Compliance by band")));
        check("engineer performance ran", "true",
                String.valueOf(transcript.contains("By tickets resolved")));
        check("the analytics of section 16 ran", "true",
                String.valueOf(transcript.contains("Worst categories")
                        && transcript.contains("Average resolution time")));
        check("the audit trail ran", "true",
                String.valueOf(transcript.contains("recorded actions")));

        check("a manager is offered nothing that changes a ticket", "false",
                String.valueOf(transcript.contains("Assign Engineer")
                        || transcript.contains("Close Ticket")
                        || transcript.contains("Update Priority")));
        check("nothing failed unexpectedly", "true", String.valueOf(noFailures(transcript)));
    }

    /* ---------- 11. Raising a ticket, then putting it back ---------- */

    private static void verifyRaisingRollsBack(DAOFactory factory) {
        section("11. Raising a ticket through section 13's own screen");

        Customer customer = ticketableCustomer(factory);
        if (customer == null) {
            check("a seeded customer with an active service was found", "true", "false");
            return;
        }
        UserSession session = sessionFor(Role.CUSTOMER, customer.getId(), null);
        String[] transcript = new String[1];

        TransactionTemplate.run(context -> {
            Savepoint marker = context.savepoint("console_verification");
            try {
                Capture capture = new Capture("2",
                        "1",
                        "1",
                        "Verification probe raised from the console harness",
                        "",
                        "8");
                new CustomerDashboard(session, capture.console(), new TicketServiceImpl(),
                        new NotificationServiceImpl()).open();
                transcript[0] = capture.transcript();
            } finally {
                context.rollbackTo(marker);
            }
        });

        String raised = firstTicketNumberIn(transcript[0]);
        if (!"(none)".equals(raised)) {
            ISSUED.add(raised);
        }

        check("the screen reported a ticket number", "true",
                String.valueOf(!"(none)".equals(raised)));
        check("  and the priority the system derived", "true",
                String.valueOf(transcript[0].contains("Priority ")));
        check("  and the deadline section 6 set", "true",
                String.valueOf(transcript[0].contains("answer due by")));
        check("nothing failed unexpectedly", "true", String.valueOf(noFailures(transcript[0])));

        check("the rollback took the ticket away again", "false", String.valueOf(
                factory.getTroubleTicketDAO().findByTicketNumber(raised).isPresent()));
    }

    /* ---------- 12. Section 2's forgotten password ---------- */

    private static void verifyForgottenPassword(DAOFactory factory) {
        section("12. Section 2's forgotten password");

        AuthenticationService authentication = new AuthenticationServiceImpl();

        String unknown = authentication.beginPasswordReset("nobody-by-this-name");
        check("an unknown name gets no code", "false",
                String.valueOf(unknown.contains("verification code is")));
        check("  and a neutral answer", "true",
                String.valueOf(unknown.contains("If that account exists")));

        Optional<UserAccount> account = usableAccount(factory);
        if (!account.isPresent()) {
            check("a seeded account that could be reset was found", "true", "false");
            return;
        }
        String username = account.get().getUsername();

        check("an unknown name is refused the same way", "AuthenticationException",
                nameOfThrown(() -> authentication.completePasswordReset("nobody-by-this-name",
                        "000000", strong())));

        // Everything below holds a live code, and a live code accepted is
        // a password really changed. So it all runs inside a transaction
        // that is rolled back whatever happens, and the stored hash is
        // compared afterwards: if a wrong attempt ever stopped burning the
        // code, this is what notices, without the account paying for it.
        TransactionTemplate.run(context -> {
            Savepoint marker = context.savepoint("console_reset");
            try {
                String first = authentication.beginPasswordReset(username);
                check("a real account gets a code", "true",
                        String.valueOf(first.contains("Your TSATMS verification code is")));
                check("  rendered as the simulated message the login uses", "true",
                        String.valueOf(first.contains("SIMULATED SMS to")));
                check("  of the length the configuration sets", "true", String.valueOf(
                        codeIn(first).matches("\\d{" + AppConstants.OTP_LENGTH + "}")));

                String second = authentication.beginPasswordReset(username);
                check("asking again abandons the first code", "false",
                        String.valueOf(codeIn(first).equals(codeIn(second))));
                check("  and the abandoned one no longer opens anything",
                        "AuthenticationException",
                        nameOfThrown(() -> authentication.completePasswordReset(username,
                                codeIn(first), strong())));

                // That attempt discarded the second code as well, so each
                // check from here starts with a fresh one.
                String third = authentication.beginPasswordReset(username);
                check("a wrong code is refused", "AuthenticationException",
                        nameOfThrown(() -> authentication.completePasswordReset(username,
                                "000000", strong())));
                check("  and burns the code, so even the right one fails after it",
                        "AuthenticationException",
                        nameOfThrown(() -> authentication.completePasswordReset(username,
                                codeIn(third), strong())));

                String fourth = authentication.beginPasswordReset(username);
                check("a new password too weak for the policy is refused",
                        "ValidationException",
                        nameOfThrown(() -> authentication.completePasswordReset(username,
                                codeIn(fourth), "abc".toCharArray())));
            } finally {
                context.rollbackTo(marker);
            }
        });

        check("through all of which the stored password never moved", "true", String.valueOf(
                factory.getUserDAO().findByUsername(username)
                        .map(UserAccount::getPasswordHash)
                        .orElse("(gone)")
                        .equals(account.get().getPasswordHash())));
    }

    /** A password the policy accepts, fresh each time since it gets cleared. */
    private static char[] strong() {
        return "Verify#2026x".toCharArray();
    }

    private static String codeIn(String renderedMessage) {
        Matcher matcher = OTP_CODE.matcher(renderedMessage);
        return matcher.find() ? matcher.group(1) : "(none)";
    }

    /* ---------- Driving a menu ---------- */

    /**
     * A console wired to a script and a buffer, so a screen can be run and
     * then read back.
     */
    private static final class Capture {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final ConsoleReader console;

        private Capture(String... keystrokes) {
            StringBuilder typed = new StringBuilder();
            for (String keystroke : keystrokes) {
                typed.append(keystroke).append(System.lineSeparator());
            }
            this.console = new ConsoleReader(
                    new ByteArrayInputStream(typed.toString().getBytes(Charset.defaultCharset())),
                    new PrintStream(buffer, true));
        }

        private ConsoleReader console() {
            return console;
        }

        private String transcript() {
            return new String(buffer.toByteArray(), Charset.defaultCharset());
        }
    }

    private static String drive(Menu menu, UserSession session, String... keystrokes) {
        Capture capture = new Capture(keystrokes);
        menu.runUntilExit(capture.console(), session);
        return capture.transcript();
    }

    /**
     * The menu the framework checks run against: one plain option, one the
     * customer may not have, one that breaks, one that refuses, and a way
     * out.
     */
    private static Menu probeMenu(int[] ran) {
        return Menu.titled("Probe")
                .option("First", () -> ran[0]++)
                .guarded("Guarded", Permission.MANAGE_USERS, () -> ran[0] += 100)
                .option("Throws", () -> {
                    throw new IllegalStateException("deliberate");
                })
                .option("Refuses", () -> {
                    throw new ValidationException("deliberate refusal");
                })
                .exit("Leave")
                .build();
    }

    private static MainMenu mainMenu(DAOFactory factory, ConsoleReader console) {
        return new MainMenu(console, new AuthenticationServiceImpl(), new TicketServiceImpl(),
                new EngineerAssignmentServiceImpl(), new EscalationServiceImpl(),
                new SlaServiceImpl(), new NotificationServiceImpl(), new ReportServiceImpl(),
                factory);
    }

    /* ---------- Finding something to look at ---------- */

    /** The customer with the most tickets, so the screens have rows on them. */
    private static Customer busiestCustomer(DAOFactory factory) {
        Customer busiest = null;
        int most = 0;
        for (Customer candidate : factory.getCustomerDAO().findAll()) {
            int count = factory.getTroubleTicketDAO().findByCustomerId(candidate.getId()).size();
            if (count > most) {
                most = count;
                busiest = candidate;
            }
        }
        return busiest;
    }

    /** A customer who could raise a ticket now, for the rollback check. */
    private static Customer ticketableCustomer(DAOFactory factory) {
        TicketServiceImpl tickets = new TicketServiceImpl();
        for (Customer candidate : factory.getCustomerDAO().findAll()) {
            UserSession session = sessionFor(Role.CUSTOMER, candidate.getId(), null);
            if (!tickets.ticketableServicesFor(session, candidate.getId()).isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    private static NetworkEngineer busiestEngineer(DAOFactory factory) {
        NetworkEngineer busiest = null;
        int most = -1;
        for (NetworkEngineer candidate : factory.getNetworkEngineerDAO().findAll()) {
            int count = factory.getTroubleTicketDAO().findByEngineerId(candidate.getId()).size();
            if (count > most) {
                most = count;
                busiest = candidate;
            }
        }
        return busiest;
    }

    private static String firstTicketOf(DAOFactory factory, Long customerId) {
        List<TroubleTicket> owned = factory.getTroubleTicketDAO().findByCustomerId(customerId);
        return owned.isEmpty() ? "TT-0000-000000" : owned.get(0).getTicketNumber();
    }

    /**
     * An account a reset could actually run against, since a disabled or
     * locked one is answered with the neutral line and would prove
     * nothing about the rest of the flow.
     */
    private static Optional<UserAccount> usableAccount(DAOFactory factory) {
        for (UserAccount candidate : factory.getUserDAO().findAll()) {
            if (candidate.getAccountStatus() == AccountStatus.ACTIVE
                    && !candidate.isCurrentlyLocked()
                    && candidate.hasUsablePassword()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /* ---------- Reading a transcript ---------- */

    /**
     * Whether the run avoided the two lines a dashboard prints when
     * something it did not expect went wrong.
     */
    private static boolean noFailures(String transcript) {
        return transcript != null && !transcript.contains("That did not work:")
                && !transcript.contains("The details are in the log.");
    }

    private static String firstTicketNumberIn(String transcript) {
        if (transcript == null) {
            return "(none)";
        }
        Matcher matcher = TICKET_NUMBER.matcher(transcript);
        return matcher.find() ? matcher.group() : "(none)";
    }

    private static String lineContaining(String[] lines, String needle) {
        return Arrays.stream(lines).filter(line -> line.contains(needle)).findFirst().orElse("");
    }

    private static int countMatching(String[] lines, String regex) {
        int found = 0;
        for (String line : lines) {
            if (line.matches(regex)) {
                found++;
            }
        }
        return found;
    }

    private static String labelsOf(Menu menu) {
        StringBuilder labels = new StringBuilder();
        for (MenuOption option : menu.getOptions()) {
            if (labels.length() > 0) {
                labels.append(", ");
            }
            labels.append(option.getLabel());
        }
        return labels.toString();
    }

    /* ---------- Shared ---------- */

    private static UserSession sessionFor(Role role, Long customerId, Long engineerId) {
        String username = "vfy-console-" + role.getCode().toLowerCase();
        UserAccount account = new UserAccount(username, "Verification Actor",
                username + "@example.test", role);
        account.setId(-1L);
        return new UserSession(account, null, customerId, engineerId);
    }

    /**
     * Last resort cleanup. The rollback should have dealt with the probe,
     * so anything found here means the transaction did not hold.
     */
    private static int removeIssuedTickets(TroubleTicketDAO tickets) {
        int removed = 0;
        for (String number : ISSUED) {
            Optional<TroubleTicket> stray = tickets.findByTicketNumber(number);
            if (stray.isPresent() && tickets.deleteById(stray.get().getId())) {
                removed++;
            }
        }
        return removed;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String nameOfThrown(Runnable work) {
        try {
            work.run();
            return "(nothing thrown)";
        } catch (RuntimeException thrown) {
            return thrown.getClass().getSimpleName();
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("  " + title);
    }

    private static void check(String label, String expected, String actual) {
        checksRun++;
        boolean passed = expected.equals(actual);
        if (!passed) {
            checksFailed++;
        }
        System.out.println(String.format("    [%s] %-58s expected=%-16s actual=%s",
                passed ? "PASS" : "FAIL", label, expected, actual));
    }
}
