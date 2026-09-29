package com.amdocs.telecom.main;

import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.TransactionTemplate;
import com.amdocs.telecom.dto.DashboardStatsDTO;
import com.amdocs.telecom.dto.TicketDetailDTO;
import com.amdocs.telecom.model.AuditLog;
import com.amdocs.telecom.model.Customer;
import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.EscalationHistory;
import com.amdocs.telecom.model.Feedback;
import com.amdocs.telecom.model.NetworkEngineer;
import com.amdocs.telecom.model.NetworkEvent;
import com.amdocs.telecom.model.Notification;
import com.amdocs.telecom.model.TicketStatusHistory;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.UserAccount;
import com.amdocs.telecom.model.enums.NetworkEventType;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.model.enums.ResolutionCode;
import com.amdocs.telecom.model.enums.Role;
import com.amdocs.telecom.model.enums.TicketStatus;
import com.amdocs.telecom.report.ReportFormat;
import com.amdocs.telecom.report.ReportKind;
import com.amdocs.telecom.report.ReportTable;
import com.amdocs.telecom.security.AccessControl;
import com.amdocs.telecom.security.CaptchaChallenge;
import com.amdocs.telecom.security.LoginResult;
import com.amdocs.telecom.security.Permission;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.AuthenticationService;
import com.amdocs.telecom.service.EngineerAssignmentService;
import com.amdocs.telecom.service.EscalationService;
import com.amdocs.telecom.service.NetworkEventService;
import com.amdocs.telecom.service.NotificationService;
import com.amdocs.telecom.service.ReportService;
import com.amdocs.telecom.service.SlaService;
import com.amdocs.telecom.service.TicketService;
import com.amdocs.telecom.service.analytics.EngineerScorecard;
import com.amdocs.telecom.service.analytics.Tally;
import com.amdocs.telecom.service.analytics.TicketAnalytics;
import com.amdocs.telecom.service.assignment.AssignmentResult;
import com.amdocs.telecom.service.assignment.EngineerMatch;
import com.amdocs.telecom.service.escalation.EscalationOutcome;
import com.amdocs.telecom.service.event.EventOutcome;
import com.amdocs.telecom.service.event.NetworkEventSimulator;
import com.amdocs.telecom.service.impl.AuthenticationServiceImpl;
import com.amdocs.telecom.service.impl.EngineerAssignmentServiceImpl;
import com.amdocs.telecom.service.impl.EscalationServiceImpl;
import com.amdocs.telecom.service.impl.NetworkEventServiceImpl;
import com.amdocs.telecom.service.impl.NotificationServiceImpl;
import com.amdocs.telecom.service.impl.ReportServiceImpl;
import com.amdocs.telecom.service.impl.SlaServiceImpl;
import com.amdocs.telecom.service.impl.TicketServiceImpl;
import com.amdocs.telecom.service.sla.SlaEvaluation;
import com.amdocs.telecom.util.AppConstants;
import com.amdocs.telecom.util.ConfigLoader;

import java.sql.Savepoint;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One incident, followed from the alarm that reported it to the feedback
 * that closed the book on it, with every section of the case study it
 * passes through named as it happens.
 *
 * <h3>Why this exists alongside the harnesses</h3>
 *
 * <p>The eleven verification harnesses each prove one layer works. None
 * of them shows the layers working together, and a system whose parts are
 * individually correct can still be wired up wrongly. This runs the whole
 * thing in one sitting: four real logins, an alarm that opens a ticket
 * by itself, an assignment that commits eight rows or none, an
 * escalation, a resolution, a closure, feedback and a report.</p>
 *
 * <p>Nothing is simulated except the network alarms, which section 11
 * asks to be simulated. The logins go through the real CAPTCHA, password
 * and one time password; the demo reads the CAPTCHA off the rendering
 * exactly as a person would, and takes the code from the simulated
 * message the login prints.</p>
 *
 * <h3>It leaves nothing behind</h3>
 *
 * <p>Everything runs inside one transaction, rolled back to a savepoint
 * at the end, so the demo can be run repeatedly on the seeded database
 * and always tells the same story. That rollback is also the last
 * demonstration in the script: it shows section 19's guarantee applied to
 * a whole day's work rather than a single statement.</p>
 */
public final class DemoRunner {

    /** How many engineers section 16's example asks to be shortlisted. */
    private static final int SHORTLIST = 3;

    /** Rows to show from a table that could be long. */
    private static final int SAMPLE_ROWS = 5;

    private static final Pattern OTP_CODE = Pattern.compile("verification code is (\\d+)");

    private final DAOFactory factory = DAOFactory.getInstance();
    private final AuthenticationService authentication = new AuthenticationServiceImpl();
    private final TicketService tickets = new TicketServiceImpl();
    private final EngineerAssignmentService assignments = new EngineerAssignmentServiceImpl();
    private final EscalationService escalations = new EscalationServiceImpl();
    private final SlaService sla = new SlaServiceImpl();
    private final NotificationService notifications = new NotificationServiceImpl();
    private final NetworkEventService events = new NetworkEventServiceImpl();
    private final ReportService reports = new ReportServiceImpl();

    private int scene;

    private DemoRunner() {
    }

    /**
     * Runs the whole story.
     *
     * @return 0 when it reached the end, 1 when it could not
     */
    public static int execute() {
        return new DemoRunner().run();
    }

    private int run() {
        System.out.println("  End to end demonstration");
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println();
        System.out.println("  One incident from the alarm that reported it to the feedback");
        System.out.println("  that closed it. Everything below happens inside a single");
        System.out.println("  transaction which is rolled back at the end, so the database");
        System.out.println("  is exactly as it was when this finishes.");

        int[] outcome = new int[1];
        TransactionTemplate.run(context -> {
            Savepoint marker = context.savepoint("demo");
            try {
                outcome[0] = tellTheStory();
            } finally {
                context.rollbackTo(marker);
            }
        });

        System.out.println();
        System.out.println("  " + AppConstants.LINE_SINGLE);
        if (outcome[0] == 0) {
            System.out.println("  The story ran to the end and was rolled back.");
        } else {
            System.out.println("  The story could not be completed. See above.");
        }
        System.out.println();
        return outcome[0];
    }

    private int tellTheStory() {
        UserSession desk = signIn(Role.SERVICE_DESK, "the service desk");
        if (desk == null) {
            return 1;
        }
        showWhatTheRoleMayDo(desk);

        String ticketNumber = alarmArrives();
        if (ticketNumber == null) {
            return 1;
        }

        showTheTicket(desk, ticketNumber);
        String employeeCode = recommendEngineers(desk, ticketNumber);
        if (!assignEngineer(desk, ticketNumber, employeeCode)) {
            return 1;
        }
        showSlaStanding(desk, ticketNumber);
        escalate(desk, ticketNumber);

        if (!engineerResolves(ticketNumber)) {
            return 1;
        }
        closeTicket(desk, ticketNumber);
        customerGivesFeedback(ticketNumber);
        showTheTrail(desk, ticketNumber);
        managerLooksAtTheOperation(ticketNumber);

        authentication.logout(desk);
        return 0;
    }

    /* ---------- Section 2 ---------- */

    /**
     * A real sign in: CAPTCHA, then password, then one time password.
     *
     * <p>The password is read from {@code security.demo.password} and
     * never printed. {@code authenticate} clears the array before it
     * returns, so it does not outlive the call either.</p>
     */
    private UserSession signIn(Role role, String who) {
        Optional<UserAccount> account = accountFor(role);
        if (!account.isPresent()) {
            say("No " + role.getDisplayName() + " account is provisioned. Run with "
                    + "--provision-users first.");
            return null;
        }
        return signIn(account.get(), who);
    }

    private UserSession signIn(UserAccount account, String who) {
        scene("2", who.substring(0, 1).toUpperCase() + who.substring(1) + " signs in");

        CaptchaChallenge challenge = authentication.issueCaptcha();
        say("A CAPTCHA is drawn, and read back the way a person would read it.");
        System.out.println(challenge.render());

        LoginResult first = authentication.authenticate(account.getUsername(),
                demoPassword(), challenge, readBack(challenge));
        field("Username", account.getUsername());
        field("Password", "checked against a " + com.amdocs.telecom.security.PasswordHasher
                .activeAlgorithm() + " hash, never printed");
        field("Result", first.getOutcome().getDescription());

        if (!first.needsOtp()) {
            say("The login stopped here: " + first.getMessage());
            return null;
        }

        String delivery = first.getOtpDelivery().orElse("");
        say("A one time password is sent. There is no SMS gateway, so it is shown:");
        System.out.println(delivery);

        LoginResult second = authentication.verifyOtp(account.getUsername(), codeIn(delivery));
        if (!second.isAuthenticated()) {
            say("The code was refused: " + second.getMessage());
            return null;
        }

        UserSession session = second.getSession().get();
        field("Signed in", session.describe());
        field("Recorded", "one login_history row, stamped again at sign out");
        return session;
    }

    private void showWhatTheRoleMayDo(UserSession session) {
        say("Role based access, from the matrix in section 3:");
        for (Permission permission : AccessControl.permissionsOf(session.getRole())) {
            System.out.println("      - " + permission.getDescription());
        }
        say("Anything outside that list is refused by the service, not by the menu.");
    }

    /* ---------- Section 11 ---------- */

    /**
     * An alarm of the shape section 11 gives, processed into a ticket.
     *
     * <p>Regions are tried in turn because an alarm only opens a ticket
     * when it names a region with an active service behind it, and when
     * no automatic ticket is already open for the same fault. Both are
     * the consumer doing its job rather than obstacles to work around.</p>
     */
    private String alarmArrives() {
        scene("11", "An alarm arrives from the network");

        for (Region region : Region.values()) {
            NetworkEvent alarm = NetworkEventSimulator.build(
                    "NE-" + String.format("%06d", (System.nanoTime() % 900000L) + 1000L),
                    nodeFor(region), NetworkEventType.LINK_DOWN, region);

            NetworkEvent stored = events.record(alarm);
            EventOutcome outcome = events.process(stored);

            if (outcome.isTicketRaised()) {
                field("Event ID", stored.getEventReference());
                field("Network Node", stored.getNetworkNode());
                field("Event Type", stored.getEventType().getDisplayName());
                field("Severity", stored.getSeverity().getDisplayName());
                field("Event Time", Displayable.formatDateTime(stored.getEventTime()));
                say("The consumer decided this was worth a ticket and opened one "
                        + "with no person involved.");
                field("Outcome", outcome.getMessage());
                return outcome.findTicketNumber().orElse(null);
            }
            say(stored.getEventReference() + " in " + region.getDisplayName()
                    + ": " + outcome.getMessage());
        }

        say("No region produced a new ticket, which means every one of them already "
                + "has an outage ticket open. Nothing further to follow.");
        return null;
    }

    /* ---------- Sections 4, 5, 6 and 8 ---------- */

    private void showTheTicket(UserSession desk, String ticketNumber) {
        scene("5", "The ticket that was opened");

        TicketDetailDTO ticket = tickets.track(desk, ticketNumber);
        System.out.println(indent(ticket.toDetailBlock()));
        say("Category and priority were derived from the alarm and the customer, "
                + "and the deadline from the SLA band in section 8.");
    }

    /* ---------- Sections 7 and 16 ---------- */

    private String recommendEngineers(UserSession desk, String ticketNumber) {
        scene("7", "Who should take it");

        say("Section 16's example: the " + SHORTLIST + " engineers with the lowest "
                + "workload who have the right specialisation and are available.");
        List<EngineerMatch> shortlist = assignments.recommend(desk, ticketNumber, SHORTLIST);
        if (shortlist.isEmpty()) {
            say("Nobody matches the skill and region, so the assignment will be refused.");
            return null;
        }
        for (EngineerMatch match : shortlist) {
            System.out.println("      " + match.toSummaryLine());
        }
        say("Ranked by specialisation, region, availability, experience and current "
                + "workload, through Stream, Comparator and Optional.");
        return shortlist.get(0).getEngineer().getEmployeeCode();
    }

    /* ---------- Section 19 ---------- */

    private boolean assignEngineer(UserSession desk, String ticketNumber, String employeeCode) {
        scene("19", "Assignment, as one transaction");

        long historyBefore = tickets.historyFor(desk, ticketNumber).size();
        long notificationsBefore = notificationsFor(ticketNumber).size();
        long auditBefore = auditRowsFor(ticketNumber);

        AssignmentResult result = employeeCode == null
                ? assignments.autoAssign(desk, ticketNumber)
                : assignments.assign(desk, ticketNumber, employeeCode);
        field("Result", result.getMessage());
        if (!result.isAssigned()) {
            return false;
        }

        TicketDetailDTO ticket = tickets.track(desk, ticketNumber);
        say("The one commit covered every row the diagram in section 19 lists:");
        field("  Ticket updated", ticket.getStatus().getDisplayName() + ", engineer "
                + ticket.findEngineerCode().orElse("-"));
        field("  Status history", (tickets.historyFor(desk, ticketNumber).size() - historyBefore)
                + " new row");
        field("  Notification", (notificationsFor(ticketNumber).size()
                - notificationsBefore) + " new row");
        field("  Audit record", (auditRowsFor(ticketNumber) - auditBefore) + " new row");
        field("  Engineer workload", "incremented, refused if it would exceed capacity");
        say("Any one of those failing rolls the whole thing back, so a ticket can "
                + "never show an engineer who was never told about it.");
        return true;
    }

    /* ---------- Section 8 ---------- */

    private void showSlaStanding(UserSession desk, String ticketNumber) {
        scene("8", "What the SLA says");

        SlaEvaluation verdict = sla.evaluateByTicketNumber(ticketNumber);
        field("Priority", verdict.getPriority().getDisplayName());
        field("Deadline", Displayable.formatDateTime(verdict.getResolutionDeadline()));
        field("Remaining", verdict.findMinutesRemaining()
                .map(minutes -> minutes + " minute(s)").orElse("-"));
        field("Consumed", String.format("%.1f%% of the allowance",
                verdict.getConsumedPercent()));
        field("Standing", verdict.getLiveStatus().getDisplayName());
        say("The bands themselves: " + sla.describeBands());

        List<SlaEvaluation> breached = sla.breached();
        say("Across the whole operation right now, " + breached.size()
                + " open ticket(s) have already breached and " + sla.atRisk().size()
                + " are at risk.");
    }

    /* ---------- Section 9 ---------- */

    private void escalate(UserSession desk, String ticketNumber) {
        scene("9", "Escalating it");

        say(escalations.explain(desk, ticketNumber));
        EscalationOutcome outcome = escalations.escalate(desk, ticketNumber,
                "Critical outage, escalating for visibility");
        field("Result", outcome.getMessage());

        List<EscalationHistory> ladder = escalations.history(desk, ticketNumber);
        for (EscalationHistory step : ladder) {
            System.out.println("      " + step.toSummaryLine());
        }
        say("Critical tickets are taken off the queue before lower priority ones, "
                + "which is what the PriorityQueue in the sweep is for.");
    }

    /* ---------- Section 10 ---------- */

    private boolean engineerResolves(String ticketNumber) {
        TroubleTicket ticket = factory.getTroubleTicketDAO()
                .findByTicketNumber(ticketNumber).orElse(null);
        if (ticket == null || ticket.getAssignedEngineerId() == null) {
            say("The ticket has no engineer, so there is nobody to resolve it.");
            return false;
        }

        NetworkEngineer engineer = factory.getNetworkEngineerDAO()
                .getById(ticket.getAssignedEngineerId());
        UserSession session = signInAs(engineer.getUserId(), "the assigned engineer");
        if (session == null) {
            say("Engineer " + engineer.getEmployeeCode()
                    + " has no login account, so the story cannot continue as them.");
            return false;
        }

        scene("10", "The engineer works it");

        tickets.changeStatus(session, ticketNumber, TicketStatus.IN_PROGRESS,
                "Investigating the reported link failure");
        say("Moving to In Progress also stamps the first response, which is what "
                + "the response half of the SLA is measured against.");

        tickets.recordDiagnosis(session, ticketNumber,
                "Fibre pair cut during civil works at the aggregation site");
        say("Root cause recorded while the diagnosis is still going on.");

        tickets.resolve(session, ticketNumber, ResolutionCode.FIBER_CUT,
                "Fibre pair cut during civil works at the aggregation site",
                "Spliced the damaged pair and re-tested the link end to end");

        TicketDetailDTO resolved = tickets.track(session, ticketNumber);
        field("Resolution Code", ResolutionCode.FIBER_CUT.getDisplayName());
        field("Resolution Date", Displayable.formatDateTime(resolved.getResolutionDate()));
        field("Took", resolved.getResolutionHours() == null ? "-"
                : String.format("%.2f hours", resolved.getResolutionHours()));
        field("Against the SLA", resolved.getLiveSlaStatus().getDisplayName());

        authentication.logout(session);
        return true;
    }

    /* ---------- Section 14 ---------- */

    private void closeTicket(UserSession desk, String ticketNumber) {
        scene("14", "The service desk closes it");

        tickets.close(desk, ticketNumber, "Customer confirmed the service is back");
        TicketDetailDTO closed = tickets.track(desk, ticketNumber);
        field("Status", closed.getStatus().getDisplayName());
        say("Which the desk may do, and could not have done before the engineer "
                + "resolved it.");
    }

    /* ---------- Section 13 ---------- */

    private void customerGivesFeedback(String ticketNumber) {
        TroubleTicket ticket = factory.getTroubleTicketDAO()
                .findByTicketNumber(ticketNumber).orElse(null);
        if (ticket == null) {
            return;
        }
        Customer customer = factory.getCustomerDAO().getById(ticket.getCustomerId());
        UserSession session = signInAs(customer.getUserId(), "the customer");
        if (session == null) {
            say("Customer " + customer.getCustomerNumber() + " has no login account, "
                    + "so the feedback step is skipped.");
            return;
        }

        scene("13", "The customer looks at it and rates it");

        TicketDetailDTO theirs = tickets.track(session, ticketNumber);
        say("They see their own ticket, and only their own:");
        System.out.println("      " + theirs.toSummaryLine());

        Feedback feedback = tickets.submitFeedback(session, ticketNumber, 4,
                "Kept informed throughout and fixed the same day");
        field("Feedback", feedback.toSummaryLine());

        authentication.logout(session);
    }

    /* ---------- Section 10's history ---------- */

    private void showTheTrail(UserSession desk, String ticketNumber) {
        scene("10", "The complete history");

        for (TicketStatusHistory step : tickets.historyFor(desk, ticketNumber)) {
            System.out.println("      " + step.toSummaryLine());
        }
        say("Every move is here because each one was written in the same "
                + "transaction as the change it records.");

        List<AuditLog> audit = factory.getAuditLogDAO()
                .findByEntity("TROUBLE_TICKET", ticketNumber);
        say("And " + audit.size() + " audit row(s) against the same ticket, "
                + "naming who did what.");
    }

    /* ---------- Sections 15, 16 and 18 ---------- */

    private void managerLooksAtTheOperation(String ticketNumber) {
        UserSession manager = signIn(Role.NETWORK_MANAGER, "the network manager");
        if (manager == null) {
            return;
        }

        scene("12", "Every notification the story raised");
        // Only a manager may ask this. A notification belongs to whoever it
        // was addressed to, so reading everybody's is an audit power and the
        // service refuses the service desk the same call.
        List<Notification> raised = notifications.forTicket(manager, ticketNumber);
        say("All " + raised.size() + " of them, published by the observers as "
                + "each thing happened. Several events reach more than one "
                + "person, so the recipient is shown:");
        System.out.println(String.format("      %-20s %-22s %s",
                "TO", "EVENT", "MESSAGE"));
        for (Notification notification : raised) {
            System.out.println(String.format("      %-20s %-22s %s",
                    recipientName(notification),
                    notification.getNotificationType().getDisplayName(),
                    Displayable.truncate(notification.getMessage(), 46)));
        }

        scene("15", "The operations summary");
        DashboardStatsDTO stats = factory.getReportDAO().findDashboardStats();
        System.out.println(indent(stats.toDetailBlock()));

        scene("16", "The Stream analytics behind it");
        TicketAnalytics analytics = reports.loadAnalytics();
        say("Worst incident categories:");
        for (Tally<?> tally : analytics.topCategories(SAMPLE_ROWS)) {
            System.out.println(String.format("      %-24s %6d  %6.2f%%",
                    tally.getLabel(), tally.getCount(), tally.getShare()));
        }
        say("Busiest engineers:");
        List<EngineerScorecard> busiest = analytics.engineerWorkload();
        for (int index = 0; index < Math.min(SAMPLE_ROWS, busiest.size()); index++) {
            EngineerScorecard card = busiest.get(index);
            System.out.println(String.format("      %-10s %-24s open %-4d resolved %-4d late %d",
                    card.getEmployeeCode(), card.getEngineerName(), card.getOpen(),
                    card.getResolved(), card.getBreaches()));
        }
        say(analytics.averageResolutionHours().isPresent()
                ? String.format("Average resolution time: %.2f hours",
                        analytics.averageResolutionHours().getAsDouble())
                : "Nothing has been resolved yet.");

        scene("18", "A report, built and rendered");
        ReportTable table = reports.generate(ReportKind.SLA_COMPLIANCE);
        field("Report", table.getTitle() + ", " + table.getRowCount() + " row(s)");
        System.out.println(indent(reports.preview(table, ReportFormat.TEXT)));
        say("The same table exports to CSV or TXT. Nothing is written to disk here.");

        authentication.logout(manager);
    }

    /* ---------- Signing in ---------- */

    private UserSession signInAs(Long userId, String who) {
        if (userId == null) {
            return null;
        }
        Optional<UserAccount> account = factory.getUserDAO().findById(userId);
        return account.isPresent() ? signIn(account.get(), who) : null;
    }

    private Optional<UserAccount> accountFor(Role role) {
        for (UserAccount candidate : factory.getUserDAO().findAll()) {
            if (candidate.getRole() == role && candidate.hasUsablePassword()
                    && !candidate.isCurrentlyLocked()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static char[] demoPassword() {
        return ConfigLoader.getInstance()
                .getString("security.demo.password", "").toCharArray();
    }

    /**
     * Reads the challenge off its own rendering, which is all a person at
     * the terminal has to go on.
     */
    private static String readBack(CaptchaChallenge challenge) {
        for (String line : challenge.render().split("\\R")) {
            if (line.contains("|")) {
                return line.replace("|", "").replaceAll("\\s+", "");
            }
        }
        return "";
    }

    private static String codeIn(String delivery) {
        Matcher matcher = OTP_CODE.matcher(delivery);
        return matcher.find() ? matcher.group(1) : "";
    }

    /* ---------- Narration ---------- */

    private void scene(String section, String title) {
        scene++;
        System.out.println();
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println(String.format("  %d. %s   [section %s]", scene, title, section));
        System.out.println("  " + AppConstants.LINE_SINGLE);
    }

    private static void say(String text) {
        System.out.println("    " + text);
    }

    private static void field(String label, Object value) {
        System.out.println(String.format("    %-22s : %s", label, value));
    }

    private static String indent(String block) {
        StringBuilder indented = new StringBuilder();
        for (String line : block.split("\\R", -1)) {
            indented.append("      ").append(line).append(System.lineSeparator());
        }
        return indented.toString().trim().isEmpty() ? "" : indented.toString();
    }

    private long auditRowsFor(String ticketNumber) {
        return factory.getAuditLogDAO().findByEntity("TROUBLE_TICKET", ticketNumber).size();
    }

    /**
     * Counted through the DAO rather than the service, because counting
     * rows to prove a transaction wrote them is not the same question as
     * reading somebody's inbox, and only the second needs a permission.
     */
    private List<Notification> notificationsFor(String ticketNumber) {
        return factory.getTroubleTicketDAO().findByTicketNumber(ticketNumber)
                .map(ticket -> factory.getNotificationDAO().findByTicketId(ticket.getId()))
                .orElse(java.util.Collections.<Notification>emptyList());
    }

    private String recipientName(Notification notification) {
        return factory.getUserDAO().findById(notification.getRecipientId())
                .map(UserAccount::getUsername)
                .orElse("user " + notification.getRecipientId());
    }

    /** A node name of the shape section 11's MUM-RAN-045 example uses. */
    private static String nodeFor(Region region) {
        return region.name().substring(0, 3) + "-RAN-"
                + String.format("%03d", (System.nanoTime() % 900L) + 10L);
    }
}
