package com.amdocs.telecom.util;

import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.NetworkEventDAO;
import com.amdocs.telecom.dao.TransactionTemplate;
import com.amdocs.telecom.dao.TroubleTicketDAO;
import com.amdocs.telecom.model.NetworkEvent;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.enums.EventStatus;
import com.amdocs.telecom.model.enums.NetworkEventType;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.model.enums.Severity;
import com.amdocs.telecom.report.ReportGenerator;
import com.amdocs.telecom.report.ReportKind;
import com.amdocs.telecom.report.ReportResult;
import com.amdocs.telecom.report.ReportTable;
import com.amdocs.telecom.scheduler.BackgroundServices;
import com.amdocs.telecom.scheduler.BackgroundWorker;
import com.amdocs.telecom.scheduler.NetworkEventProcessor;
import com.amdocs.telecom.scheduler.NotificationProcessor;
import com.amdocs.telecom.scheduler.SlaMonitor;
import com.amdocs.telecom.service.NetworkEventService;
import com.amdocs.telecom.service.event.EventOutcome;
import com.amdocs.telecom.service.event.NetworkEventSimulator;
import com.amdocs.telecom.service.event.TicketEvent;
import com.amdocs.telecom.service.event.TicketEventListener;
import com.amdocs.telecom.service.event.TicketEventPublisher;
import com.amdocs.telecom.service.event.TicketEventType;
import com.amdocs.telecom.service.impl.NetworkEventServiceImpl;
import com.amdocs.telecom.service.impl.ReportServiceImpl;
import com.amdocs.telecom.service.impl.SlaServiceImpl;

import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Exercises the background workers of sections 11 and 17.
 *
 * <h3>Why almost nothing here is timed</h3>
 *
 * <p>A check that sleeps and then asserts is a check that passes on a quiet
 * machine and fails on a busy one, and a concurrency suite that fails at
 * random teaches people to ignore it. So the pipeline checks wait on
 * conditions instead: {@code awaitIdle} for the queue, latches for the
 * report futures. The only place a duration is asserted is a timeout that
 * is supposed to expire.</p>
 *
 * <h3>Why the threaded section uses alarms that raise no tickets</h3>
 *
 * <p>A consumer thread cannot join this thread's transaction, because the
 * connection is held in a {@link ThreadLocal}: whatever a worker writes is
 * committed on its own connection and no rollback here will remove it. A
 * committed automatic ticket would take a real number from the live series,
 * and deleting it afterwards would hand that number back to the pool while
 * its append only audit rows stayed behind, ready to attach themselves to
 * whichever ticket is issued that number next.</p>
 *
 * <p>So the work is split. The decision, which is where the tickets are, is
 * proven on this thread inside a transaction that is rolled back. The
 * threading, which is where the races are, is proven with heartbeats and
 * link-up clears: real alarms that travel the whole pipeline and are
 * correctly ignored at the end of it. The rows they leave in
 * {@code network_events} are deleted by reference, and nothing references
 * those rows, so removing them leaves nothing dangling.</p>
 */
public final class ThreadVerification {

    private ThreadVerification() {
        throw new AssertionError("ThreadVerification is not instantiable");
    }

    /**
     * The alarm reference the case study prints in section 11, with its node,
     * type and severity.
     */
    private static final String DOCUMENT_REFERENCE = "NE-884521";
    private static final String DOCUMENT_NODE = "MUM-RAN-045";

    /**
     * Probe references sit in a band of their own so cleanup can find them
     * by prefix and can never touch a simulated alarm.
     */
    private static final String PROBE_PREFIX = "NE-99";

    private static final long IDLE_TIMEOUT_MILLIS = 20000L;

    private static int checksRun;
    private static int checksFailed;

    /** Every probe reference written during this run, for cleanup. */
    private static final List<String> written = new ArrayList<String>();

    /**
     * Runs every check and prints a report.
     *
     * @return 0 when everything passed, 1 otherwise
     */
    public static int execute() {
        checksRun = 0;
        checksFailed = 0;
        written.clear();

        DAOFactory factory = DAOFactory.getInstance();

        System.out.println("  Multithreading verification");
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println();

        try {
            verifySimulator();
            verifyDecision(factory);
            verifyPipeline(factory);
            verifyFailingConsumer(factory);
            verifyShutdown(factory);
            verifySlaMonitor(factory);
            verifyNotificationProcessor(factory);
            verifyReportFutures(factory);
            verifyWiring();
        } catch (RuntimeException failure) {
            checksFailed++;
            System.out.println("  Verification aborted: " + failure);
            AppLogger.error(ThreadVerification.class, "Thread verification aborted", failure);
        } finally {
            int strays = removeProbes(factory.getNetworkEventDAO());
            if (strays > 0) {
                System.out.println();
                System.out.println("  Removed " + strays + " probe alarm(s)");
            }
        }

        System.out.println();
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println("  " + (checksRun - checksFailed) + " of " + checksRun
                + " checks passed");
        System.out.println();

        if (checksFailed == 0) {
            System.out.println("  The background workers are running against the live database.");
            AppLogger.info(ThreadVerification.class,
                    "Thread verification passed " + checksRun + " checks");
        } else {
            System.out.println("  " + checksFailed + " check(s) failed. See the detail above.");
            AppLogger.warn(ThreadVerification.class,
                    "Thread verification had " + checksFailed + " failure(s)");
        }
        System.out.println();
        return checksFailed == 0 ? 0 : 1;
    }

    /* ---------- 1. The simulated alarm stream ---------- */

    private static void verifySimulator() {
        section("1. The alarm stream of section 11");

        NetworkEvent sample = NetworkEventSimulator.build(DOCUMENT_REFERENCE, DOCUMENT_NODE,
                NetworkEventType.LINK_DOWN, Region.WEST);
        check("the document's worked example builds", DOCUMENT_REFERENCE,
                sample.getEventReference());
        check("  on the node it names", DOCUMENT_NODE, sample.getNetworkNode());
        check("  with the type it names", "LINK_DOWN", sample.getEventType().name());
        check("  at the severity it names", "CRITICAL", sample.getSeverity().name());
        check("  and it is worth a ticket", "true", String.valueOf(sample.warrantsTicket()));
        check("  arriving unprocessed", "RECEIVED", sample.getEventStatus().name());

        NetworkEventSimulator simulator = new NetworkEventSimulator(884521);
        NetworkEvent first = simulator.next();
        check("a fixed start gives a predictable reference", DOCUMENT_REFERENCE,
                first.getEventReference());

        List<NetworkEvent> batch = new NetworkEventSimulator(100000).next(200);
        check("two hundred alarms come back", "200", String.valueOf(batch.size()));
        check("  every reference distinct", "200", String.valueOf(distinctReferences(batch)));
        check("  every reference in the document's shape", "true",
                String.valueOf(allMatch(batch, "NE-\\d{6}")));
        check("  every node named for its region", "true", String.valueOf(nodesMatchRegion(batch)));

        // The mix matters: a stream of nothing but outages would make the
        // consumer's decision look like no decision at all.
        long worthATicket = batch.stream().filter(NetworkEvent::warrantsTicket).count();
        check("most of the stream is worth a ticket", "120", String.valueOf(worthATicket));
        check("  but a good part of it is not", "80",
                String.valueOf(batch.size() - worthATicket));

        check("a heartbeat is never worth a ticket", "false", String.valueOf(
                NetworkEventSimulator.build("NE-990001", "DEL-RAN-001",
                        NetworkEventType.HEARTBEAT, Region.NORTH).warrantsTicket()));
        check("  and carries no severity to speak of", "INFO",
                NetworkEventSimulator.build("NE-990002", "DEL-RAN-001",
                        NetworkEventType.HEARTBEAT, Region.NORTH).getSeverity().name());
        check("a link coming back up is not a fault either", "false", String.valueOf(
                NetworkEventSimulator.build("NE-990003", "MUM-RAN-002",
                        NetworkEventType.LINK_UP, Region.WEST).warrantsTicket()));
        check("an unreachable node is", "true", String.valueOf(
                NetworkEventSimulator.build("NE-990004", "KOL-RAN-003",
                        NetworkEventType.NODE_UNREACHABLE, Region.EAST).warrantsTicket()));
        // A node that has stopped answering is a connectivity failure for
        // whoever is behind it, which is a different ticket category from a
        // link that has dropped.
        check("  and it maps to a category a ticket can carry", "NO_CONNECTIVITY",
                NetworkEventType.NODE_UNREACHABLE.getMappedCategory().name());
        check("a dropped link maps to an outage", "NETWORK_OUTAGE",
                NetworkEventType.LINK_DOWN.getMappedCategory().name());
    }

    /* ---------- 2. What one alarm is worth ---------- */

    private static void verifyDecision(DAOFactory factory) {
        section("2. What one alarm is worth");

        NetworkEventDAO events = factory.getNetworkEventDAO();
        // A publisher of its own with nothing listening: the decision is what
        // is under test here, not the notifications Phase 8 already proved.
        NetworkEventService service = new NetworkEventServiceImpl(factory, new SlaServiceImpl(),
                new TicketEventPublisher(), new NetworkEventSimulator(990100));

        TransactionTemplate.run(context -> {
            Savepoint marker = context.savepoint("thread_verification_decision");
            try {
                NetworkEvent outage = service.record(probe("NE-990101",
                        NetworkEventType.LINK_DOWN, Region.WEST));
                check("an alarm is stored as received", "RECEIVED",
                        outage.getEventStatus().name());
                check("  and can be found by its reference", "true", String.valueOf(
                        events.findByReference("NE-990101").isPresent()));
                check("  the same reference cannot arrive twice", "DuplicateResourceException",
                        nameOfThrown(() -> service.record(probe("NE-990101",
                                NetworkEventType.LINK_DOWN, Region.WEST))));

                EventOutcome raised = service.process(outage);
                check("an outage raises a ticket", "true",
                        String.valueOf(raised.isTicketRaised()));
                check("  the consumer claimed it", "true", String.valueOf(raised.isClaimed()));
                check("  the row says a ticket came of it", "TICKET_CREATED",
                        statusOf(raised));
                check("  and points at it", "true",
                        String.valueOf(raised.findTicketId().isPresent()));

                NetworkEvent stored = events.findByReference("NE-990101").get();
                check("the stored row agrees", "TICKET_CREATED", stored.getEventStatus().name());
                check("  and is marked processed", "true", String.valueOf(stored.isProcessed()));
                check("  carrying the ticket it raised", "true",
                        String.valueOf(stored.findTicketId().isPresent()));

                TroubleTicket ticket = factory.getTroubleTicketDAO()
                        .getById(raised.findTicketId().get());
                check("the ticket knows nobody typed it", "true",
                        String.valueOf(ticket.isAutoCreated()));
                check("  it opens unassigned", "OPEN", ticket.getStatus().name());
                check("  with the category the alarm maps to", "NETWORK_OUTAGE",
                        ticket.getCategory().name());
                check("  the severity the alarm carried", "CRITICAL",
                        ticket.getSeverity().name());
                check("  its SLA deadlines already stamped", "true",
                        String.valueOf(ticket.getSlaDeadline() != null
                                && ticket.getSlaResponseDeadline() != null));
                check("  and the alarm named in its description", "true",
                        String.valueOf(ticket.getDescription().contains("NE-990101")));

                // The fibre is still cut, so the next alarm about it belongs
                // on the ticket that is already open rather than on a new one.
                NetworkEvent aftershock = service.record(probe("NE-990102",
                        NetworkEventType.LINK_DOWN, Region.WEST));
                EventOutcome folded = service.process(aftershock);
                check("a second alarm for the same fault opens nothing new", "false",
                        String.valueOf(folded.isTicketRaised()));
                check("  it joins the ticket already open", ticket.getId().toString(),
                        String.valueOf(folded.findTicketId().orElse(null)));
                check("  and its row records which one", "TICKET_CREATED", statusOf(folded));

                NetworkEvent heartbeat = service.record(probe("NE-990103",
                        NetworkEventType.HEARTBEAT, Region.WEST));
                EventOutcome quiet = service.process(heartbeat);
                check("a heartbeat is noted and dropped", "IGNORED", statusOf(quiet));
                check("  with no ticket behind it", "false",
                        String.valueOf(quiet.findTicketId().isPresent()));
                check("  and a reason a person can read", "true",
                        String.valueOf(quiet.getMessage() != null
                                && !quiet.getMessage().trim().isEmpty()));

                NetworkEvent nowhere = probe("NE-990104", NetworkEventType.LINK_DOWN, null);
                service.record(nowhere);
                check("an alarm from nowhere in particular is ignored", "IGNORED",
                        statusOf(service.process(nowhere)));

                // The claim is the whole defence against two consumers
                // ticketing one alarm, so it is checked directly.
                NetworkEvent contested = service.record(probe("NE-990105",
                        NetworkEventType.HEARTBEAT, Region.NORTH));
                EventOutcome won = service.process(contested);
                EventOutcome lost = service.process(contested);
                check("the first consumer to claim an alarm gets it", "true",
                        String.valueOf(won.isClaimed()));
                check("  the second is told it lost", "false",
                        String.valueOf(lost.isClaimed()));
                check("  and the loser writes nothing", "(none)", statusOf(lost));

                Map<EventStatus, Long> tally = service.countByStatus();
                check("the stream can be counted by state", "true",
                        String.valueOf(tally.containsKey(EventStatus.TICKET_CREATED)
                                && tally.containsKey(EventStatus.IGNORED)));
            } finally {
                context.rollbackTo(marker);
            }
        });

        check("nothing was left behind", "0", String.valueOf(countProbes(events,
                Arrays.asList("NE-990101", "NE-990102", "NE-990103", "NE-990104", "NE-990105"))));
    }

    /* ---------- 3. The queue and its consumers ---------- */

    private static void verifyPipeline(DAOFactory factory) {
        section("3. Producer, queue and consumers");

        NetworkEventDAO events = factory.getNetworkEventDAO();
        NetworkEventService service = new NetworkEventServiceImpl(factory, new SlaServiceImpl(),
                new TicketEventPublisher(), new NetworkEventSimulator(990200));

        // Heartbeats on purpose: they travel the whole pipeline and are
        // ignored at the end of it, so the threading is exercised for real
        // without a single ticket being committed.
        List<NetworkEvent> alarms = quietAlarms("NE-9903", 24);
        service.recordAll(alarms);
        List<NetworkEvent> stored = reload(events, alarms);
        check("twenty four alarms are waiting in the table", "24",
                String.valueOf(stored.size()));

        NetworkEventProcessor processor = new NetworkEventProcessor(service::process, 4, 64);
        check("the processor starts stopped", "false", String.valueOf(processor.isRunning()));
        check("  with four consumers", "4", String.valueOf(processor.getConsumerCount()));
        check("  and room for sixty four alarms", "64", String.valueOf(processor.capacity()));

        processor.start();
        try {
            check("it is running once started", "true", String.valueOf(processor.isRunning()));
            int taken = processor.offerAll(stored);
            check("the queue takes every alarm offered", "24", String.valueOf(taken));
            check("  and counts them", "24", String.valueOf(processor.getAccepted()));

            check("the pipeline goes quiet", "true", String.valueOf(awaitIdle(processor)));
            check("  with nothing left in hand", "0", String.valueOf(processor.getPending()));
            check("  and nothing left queued", "0", String.valueOf(processor.queued().size()));

            // The invariant that matters: every alarm dealt with once, by
            // exactly one consumer, with the counters adding up.
            check("every alarm was completed", "24", String.valueOf(processor.getCompleted()));
            check("  all of them ignored, as heartbeats should be", "24",
                    String.valueOf(processor.getIgnored()));
            check("  none raised a ticket", "0", String.valueOf(processor.getTicketsRaised()));
            check("  none failed", "0", String.valueOf(processor.getFailures()));
            check("  and no two consumers claimed the same alarm", "0",
                    String.valueOf(processor.getLostClaims()));
            check("the counters add up", "true", String.valueOf(
                    processor.getCompleted() == processor.getTicketsRaised()
                            + processor.getCorrelated() + processor.getIgnored()
                            + processor.getFailures() + processor.getLostClaims()));

            check("the table agrees that all are processed", "24",
                    String.valueOf(processedCount(events, alarms)));
            check("  and none of them raised anything", "0",
                    String.valueOf(withTickets(events, alarms)));
        } finally {
            processor.stop();
        }
        check("the processor is stopped afterwards", "false",
                String.valueOf(processor.isRunning()));

        // Back pressure: a queue with room for two, and nothing taking
        // anything off it, must refuse the third rather than swallow it.
        NetworkEventProcessor tiny = new NetworkEventProcessor(event -> null, 1, 2);
        List<NetworkEvent> three = quietAlarms("NE-9904", 3);
        check("a full queue takes the first", "true", String.valueOf(tiny.offer(three.get(0))));
        check("  and the second", "true", String.valueOf(tiny.offer(three.get(1))));
        check("  then refuses the third", "false", String.valueOf(tiny.offer(three.get(2))));
        check("  counting the refusal", "1", String.valueOf(tiny.getRejected()));
        check("  and not counting it as accepted", "2", String.valueOf(tiny.getAccepted()));
        check("  the refused alarm is still waiting in the table", "false",
                String.valueOf(three.get(2).isProcessed()));
    }

    /* ---------- 4. A consumer that meets a bad alarm ---------- */

    private static void verifyFailingConsumer(DAOFactory factory) {
        section("4. One bad alarm does not stop the rest");

        NetworkEventDAO events = factory.getNetworkEventDAO();
        NetworkEventService service = new NetworkEventServiceImpl(factory, new SlaServiceImpl(),
                new TicketEventPublisher(), new NetworkEventSimulator(990400));

        List<NetworkEvent> alarms = quietAlarms("NE-9905", 8);
        service.recordAll(alarms);
        List<NetworkEvent> stored = reload(events, alarms);
        List<NetworkEvent> more = quietAlarms("NE-9906", 4);
        final String poison = stored.get(3).getEventReference();
        final AtomicInteger seen = new AtomicInteger();

        // A handler that throws on one alarm, the way a genuinely corrupt
        // event would. A single consumer, so the failure and the recovery
        // are on the same thread and there is no doubt it survived.
        NetworkEventProcessor processor = new NetworkEventProcessor(event -> {
            seen.incrementAndGet();
            if (poison.equals(event.getEventReference())) {
                throw new IllegalStateException("This alarm is deliberately broken");
            }
            return service.process(event);
        }, 1, 32);

        processor.start();
        try {
            processor.offerAll(stored);
            check("the pipeline goes quiet despite the bad alarm", "true",
                    String.valueOf(awaitIdle(processor)));
            check("  the consumer saw every alarm", "8", String.valueOf(seen.get()));
            check("  and completed every one", "8", String.valueOf(processor.getCompleted()));
            check("  exactly one is counted as failed", "1",
                    String.valueOf(processor.getFailures()));
            check("  the other seven went through", "7",
                    String.valueOf(processor.getIgnored()));
            check("the consumer is still alive", "true", String.valueOf(processor.isRunning()));

            // Proof it really is still working, not merely still marked as
            // running: give it more work after the failure.
            service.recordAll(more);
            processor.offerAll(reload(events, more));
            check("  and still takes work after the failure", "true",
                    String.valueOf(awaitIdle(processor)));
            check("  which it completed too", "12", String.valueOf(processor.getCompleted()));
        } finally {
            processor.stop();
        }

        check("the bad alarm is left unprocessed for somebody to look at", "false",
                String.valueOf(events.findByReference(poison).get().isProcessed()));
        check("  while the other eleven are done", "11",
                String.valueOf(processedCount(events, stored) + processedCount(events, more)));
    }

    /* ---------- 5. Stopping ---------- */

    private static void verifyShutdown(DAOFactory factory) {
        section("5. Stopping cleanly");

        NetworkEventDAO events = factory.getNetworkEventDAO();
        NetworkEventService service = new NetworkEventServiceImpl(factory, new SlaServiceImpl(),
                new TicketEventPublisher(), new NetworkEventSimulator(990500));

        List<NetworkEvent> alarms = quietAlarms("NE-9907", 12);
        service.recordAll(alarms);
        List<NetworkEvent> stored = reload(events, alarms);

        NetworkEventProcessor processor = new NetworkEventProcessor(service::process, 2, 32);
        processor.start();
        check("starting twice is not an error", "true",
                String.valueOf(canReach(processor::start)));
        check("  and leaves it running once", "true", String.valueOf(processor.isRunning()));

        processor.offerAll(stored);
        // stop() is called with work possibly still queued. The contract is
        // that it drains rather than abandons, which is the difference
        // between shutdown and shutdownNow.
        processor.stop();
        check("stopping drains what was accepted", "12",
                String.valueOf(processor.getCompleted()));
        check("  leaving nothing pending", "0", String.valueOf(processor.getPending()));
        check("  and nothing queued", "0", String.valueOf(processor.queued().size()));
        check("  every alarm reached the table", "12",
                String.valueOf(processedCount(events, alarms)));
        check("stopping twice is not an error", "true",
                String.valueOf(canReach(processor::stop)));
        check("  and it stays stopped", "false", String.valueOf(processor.isRunning()));
        check("the worker describes what it did", "true",
                String.valueOf(processor.describe().contains("completed 12")));
    }

    /* ---------- 6. The SLA monitor ---------- */

    private static void verifySlaMonitor(DAOFactory factory) {
        section("6. The SLA monitor");

        // A publisher of its own, carrying one counting listener. The sweep
        // therefore writes no notifications and no audit rows, and the count
        // still proves the monitor spoke.
        final CountingListener heard = new CountingListener();
        TicketEventPublisher quiet = new TicketEventPublisher().register(heard);

        SlaMonitor refresher = new SlaMonitor(new SlaServiceImpl(), factory,
                new TicketEventPublisher(), 60L, false);
        check("a monitor starts stopped", "false", String.valueOf(refresher.isRunning()));
        check("  and named for its log lines", "tsatms-sla", refresher.getName());

        TransactionTemplate.run(context -> {
            Savepoint marker = context.savepoint("thread_verification_sla");
            try {
                // A refresh only monitor: it corrects sla_status and says
                // nothing, which is the mode a console uses when it wants the
                // column current without an inbox full of warnings.
                check("a refresh only monitor announces nothing", "0",
                        String.valueOf(refresher.sweep()));
                check("  but it did sweep", "1", String.valueOf(refresher.getSweeps()));
                check("  and it is not announcing", "false",
                        String.valueOf(refresher.isAnnouncing()));

                SlaMonitor monitor = new SlaMonitor(new SlaServiceImpl(), factory, quiet,
                        60L, true);
                int told = monitor.sweep();
                check("an announcing monitor tells somebody", "true",
                        String.valueOf(told > 0));
                check("  once per ticket that needs attention", "true",
                        String.valueOf(heard.total.get() == told));
                check("  and the seeded breaches are among them", "true",
                        String.valueOf(heard.breaches.get() > 0));
                check("  remembering what it said", "true",
                        String.valueOf(monitor.getRemembered() == told));

                // The point of the memory: a monitor sweeping every minute
                // must not send the same warning every minute.
                int again = monitor.sweep();
                check("sweeping again says nothing new", "0", String.valueOf(again));
                check("  because it was suppressed, not missed", "true",
                        String.valueOf(monitor.getSuppressed() >= told));
                check("  and nobody was told twice", "true",
                        String.valueOf(heard.total.get() == told));

                monitor.resetMemory();
                check("forgetting makes it speak again", "true",
                        String.valueOf(monitor.sweep() > 0));
                check("  which is the safe direction to be wrong in", "true",
                        String.valueOf(heard.total.get() > told));

                check("it never failed", "0", String.valueOf(monitor.getFailures()));

                // A listener that throws is the realistic failure: a mail
                // gateway down should cost that alert, not the monitor.
                TicketEventPublisher broken =
                        new TicketEventPublisher().register(new FailingListener());
                SlaMonitor unlucky = new SlaMonitor(new SlaServiceImpl(), factory, broken,
                        60L, true);
                check("a broken listener does not kill the monitor", "true",
                        String.valueOf(canReach(unlucky::sweep)));
                check("  the failures are counted", "true",
                        String.valueOf(unlucky.getFailures() > 0));
                check("  nothing was recorded as announced", "0",
                        String.valueOf(unlucky.getRemembered()));
                check("  and it would try again next sweep", "true",
                        String.valueOf(unlucky.getWarnings() + unlucky.getBreaches() == 0));
            } finally {
                context.rollbackTo(marker);
            }
        });
    }

    /* ---------- 7. The notification dispatcher ---------- */

    private static void verifyNotificationProcessor(DAOFactory factory) {
        section("7. The notification dispatcher");

        NotificationProcessor dispatcher = new NotificationProcessor(factory, 10, 30L);
        check("the dispatcher starts stopped", "false", String.valueOf(dispatcher.isRunning()));
        check("  and is named for its log lines", "tsatms-notify", dispatcher.getName());

        long unread = factory.getNotificationDAO().findPendingDispatch(10).size();
        int delivered = dispatcher.dispatchNow();
        check("it delivers what is waiting", String.valueOf(unread),
                String.valueOf(delivered));
        check("  counting the sweep", "1", String.valueOf(dispatcher.getSweeps()));
        check("  and the deliveries", String.valueOf(unread),
                String.valueOf(dispatcher.getDelivered()));

        // The reason the memory exists: unread is not undelivered, so the
        // same rows come back next sweep and must not go out twice.
        int second = dispatcher.dispatchNow();
        check("a second sweep sends nothing again", "0", String.valueOf(second));
        check("  passing over what it had already sent", String.valueOf(unread),
                String.valueOf(dispatcher.getSkipped()));
        check("  and remembering them", String.valueOf(unread),
                String.valueOf(dispatcher.getRemembered()));

        dispatcher.resetMemory();
        check("forgetting clears the record", "0", String.valueOf(dispatcher.getRemembered()));
        check("  and the messages go again", String.valueOf(unread),
                String.valueOf(dispatcher.dispatchNow()));
        check("it never failed", "0", String.valueOf(dispatcher.getFailures()));
        check("  and nothing it did marked a message read", String.valueOf(unread),
                String.valueOf(factory.getNotificationDAO().findPendingDispatch(10).size()));
    }

    /* ---------- 8. Reports on a pool ---------- */

    private static void verifyReportFutures(DAOFactory factory) {
        section("8. Reports on their own threads");

        ReportGenerator reports = new ReportGenerator(new ReportServiceImpl(), 4);
        check("the generator starts stopped", "false", String.valueOf(reports.isRunning()));
        check("  and will not take work before it is started", "IllegalStateException",
                nameOfThrown(() -> reports.submit(ReportKind.SLA_COMPLIANCE)));

        reports.start();
        try {
            Future<ReportResult> pending = reports.submit(ReportKind.SLA_COMPLIANCE);
            ReportResult compliance = get(pending);
            check("a submitted report comes back", "SLA Compliance Report",
                    compliance.getKind().getDisplayName());
            check("  having run off this thread", "true",
                    String.valueOf(compliance.getProducedBy().startsWith("tsatms-report")));
            check("  not on the one that asked", "true", String.valueOf(
                    !compliance.getProducedBy().equals(Thread.currentThread().getName())));
            check("  and the future says it is done", "true",
                    String.valueOf(pending.isDone()));
            check("  it was not cancelled", "false", String.valueOf(pending.isCancelled()));

            Map<ReportKind, ReportResult> all = reports.runAll(
                    Arrays.asList(ReportKind.values()));
            check("every report runs in one batch", String.valueOf(ReportKind.values().length),
                    String.valueOf(all.size()));
            check("  SLA compliance is one row per priority", "4",
                    String.valueOf(all.get(ReportKind.SLA_COMPLIANCE).getRowCount()));
            check("  and the batch used more than one thread", "true",
                    String.valueOf(threadsUsed(all.values()) > 1));
            check("  each result knows what it cost", "true",
                    String.valueOf(all.values().stream()
                            .allMatch(result -> result.getTookMillis() >= 0)));
            check("  and the rows cannot be tampered with", "UnsupportedOperationException",
                    nameOfThrown(() -> all.get(ReportKind.SLA_COMPLIANCE).getTable()
                            .getRows().clear()));
            check("nothing failed", "0", String.valueOf(reports.getFailed()));
        } finally {
            reports.stop();
        }

        verifyFutureSemantics();
    }

    /**
     * The {@code Future} contract itself, against a source that can be held
     * open or made to fail on demand.
     *
     * <p>Timeouts and cancellations cannot be proven against a query that
     * returns in two milliseconds; the test would be racing the database.
     * A latch makes the report take exactly as long as the check needs.</p>
     */
    private static void verifyFutureSemantics() {
        final CountDownLatch hold = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);

        ReportGenerator slow = new ReportGenerator(kind -> {
            if (kind == ReportKind.CRITICAL_INCIDENT) {
                throw new IllegalStateException("This report is deliberately broken");
            }
            started.countDown();
            try {
                hold.await(30L, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Report interrupted", interrupted);
            }
            return ReportTable.of(kind).text("Result").row("one row").build();
        }, 2);

        slow.start();
        try {
            Future<ReportResult> slowOne = slow.submit(ReportKind.SLA_COMPLIANCE);
            check("a report still running is not done", "false",
                    String.valueOf(slowOne.isDone()));
            check("  and waiting with a deadline times out", "TimeoutException",
                    nameOfThrownChecked(() -> slowOne.get(200L, TimeUnit.MILLISECONDS)));

            hold.countDown();
            ReportResult finished = get(slowOne);
            check("  releasing it lets the answer through", "1",
                    String.valueOf(finished.getRowCount()));
            check("  after which it is done", "true", String.valueOf(slowOne.isDone()));

            Future<ReportResult> doomed = slow.submit(ReportKind.CRITICAL_INCIDENT);
            check("a report that throws surfaces on get", "ExecutionException",
                    nameOfThrownChecked(() -> doomed.get(10L, TimeUnit.SECONDS)));
            check("  and the generator counts it", "1", String.valueOf(slow.getFailed()));
            check("  without taking the pool down", "true", String.valueOf(slow.isRunning()));

            // A report nobody is waiting for any more should stop, and say so.
            final CountDownLatch neverReleased = new CountDownLatch(1);
            ReportGenerator cancellable = new ReportGenerator(kind -> {
                try {
                    neverReleased.await(30L, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Cancelled", interrupted);
                }
                return ReportTable.of(kind).text("Result").build();
            }, 1);
            cancellable.start();
            try {
                Future<ReportResult> abandoned = cancellable.submit(ReportKind.SLA_COMPLIANCE);
                check("a running report can be cancelled", "true",
                        String.valueOf(abandoned.cancel(true)));
                check("  the future says so", "true", String.valueOf(abandoned.isCancelled()));
                check("  and counts as done", "true", String.valueOf(abandoned.isDone()));
                check("  reading it afterwards refuses", "CancellationException",
                        nameOfThrownChecked(() -> abandoned.get(5L, TimeUnit.SECONDS)));
            } finally {
                neverReleased.countDown();
                cancellable.stop();
            }
        } finally {
            hold.countDown();
            slow.stop();
        }
    }

    /* ---------- 9. The workers as a set ---------- */

    private static void verifyWiring() {
        section("9. One place to start and stop them all");

        BackgroundServices services = BackgroundServices.getInstance();
        check("there is one set of background services", "true",
                String.valueOf(services == BackgroundServices.getInstance()));
        check("  holding the four workers section 17 asks for, plus the feeder", "5",
                String.valueOf(services.workers().size()));
        check("  in start order, producer last", "tsatms-feeder, tsatms-alarm, tsatms-sla, "
                        + "tsatms-notify, tsatms-report",
                services.workers().stream().map(BackgroundWorker::getName)
                        .collect(Collectors.joining(", ")));
        check("  every one of them wired", "true",
                String.valueOf(services.getEventService() != null
                        && services.getAlarmProcessor() != null
                        && services.getAlarmFeeder() != null
                        && services.getSlaMonitor() != null
                        && services.getNotificationProcessor() != null
                        && services.getReportGenerator() != null));
        check("  none of them running yet", "false", String.valueOf(services.isStarted()));
        check("  and stopping what never started is harmless", "true",
                String.valueOf(canReach(services::stopAll)));
        check("the console can be shown what is running", "5",
                String.valueOf(services.status().size()));
        check("  each line naming its worker", "true",
                String.valueOf(services.status().get(0).contains("tsatms-feeder")));
    }

    /* ---------- Probe alarms ---------- */

    /**
     * An alarm that will travel the pipeline and be ignored at the end of
     * it, so the threading can be exercised without committing a ticket.
     */
    private static List<NetworkEvent> quietAlarms(String prefix, int count) {
        List<NetworkEvent> batch = new ArrayList<NetworkEvent>(count);
        for (int index = 0; index < count; index++) {
            String reference = prefix + String.format("%02d", index);
            batch.add(probe(reference, index % 2 == 0
                    ? NetworkEventType.HEARTBEAT : NetworkEventType.LINK_UP, Region.NORTH));
        }
        return batch;
    }

    private static NetworkEvent probe(String reference, NetworkEventType type, Region region) {
        NetworkEvent event = NetworkEventSimulator.build(reference, "VFY-RAN-001", type,
                region == null ? Region.NORTH : region);
        if (region == null) {
            event.setRegion(null);
        }
        written.add(reference);
        return event;
    }

    /**
     * Reads the alarms back so they carry their keys, which the batch insert
     * does not populate and the claim needs.
     */
    private static List<NetworkEvent> reload(NetworkEventDAO events, List<NetworkEvent> batch) {
        List<NetworkEvent> stored = new ArrayList<NetworkEvent>(batch.size());
        for (NetworkEvent event : batch) {
            Optional<NetworkEvent> found = events.findByReference(event.getEventReference());
            if (found.isPresent()) {
                stored.add(found.get());
            }
        }
        return stored;
    }

    private static int processedCount(NetworkEventDAO events, List<NetworkEvent> batch) {
        int done = 0;
        for (NetworkEvent event : batch) {
            Optional<NetworkEvent> found = events.findByReference(event.getEventReference());
            if (found.isPresent() && found.get().isProcessed()) {
                done++;
            }
        }
        return done;
    }

    private static int withTickets(NetworkEventDAO events, List<NetworkEvent> batch) {
        int raised = 0;
        for (NetworkEvent event : batch) {
            Optional<NetworkEvent> found = events.findByReference(event.getEventReference());
            if (found.isPresent() && found.get().findTicketId().isPresent()) {
                raised++;
            }
        }
        return raised;
    }

    private static int countProbes(NetworkEventDAO events, List<String> references) {
        int found = 0;
        for (String reference : references) {
            if (events.findByReference(reference).isPresent()) {
                found++;
            }
        }
        return found;
    }

    /**
     * Removes every probe alarm this run wrote.
     *
     * <p>Nothing points at a {@code network_events} row, so deleting one
     * leaves nothing dangling. The references are remembered rather than
     * matched by prefix so a probe can never be confused with a simulated
     * alarm a real run produced.</p>
     */
    private static int removeProbes(NetworkEventDAO events) {
        int removed = 0;
        for (String reference : new HashSet<String>(written)) {
            if (!reference.startsWith(PROBE_PREFIX)) {
                continue;
            }
            try {
                Optional<NetworkEvent> stray = events.findByReference(reference);
                if (stray.isPresent() && events.deleteById(stray.get().getId())) {
                    removed++;
                }
            } catch (RuntimeException failure) {
                AppLogger.warn(ThreadVerification.class,
                        "Could not remove the probe alarm " + reference);
            }
        }
        written.clear();
        return removed;
    }

    /* ---------- Small helpers ---------- */

    private static boolean awaitIdle(NetworkEventProcessor processor) {
        try {
            return processor.awaitIdle(IDLE_TIMEOUT_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static ReportResult get(Future<ReportResult> future) {
        try {
            return future.get(30L, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for a report", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("A report did not arrive", failure);
        }
    }

    private static long threadsUsed(java.util.Collection<ReportResult> results) {
        Set<String> names = new HashSet<String>();
        for (ReportResult result : results) {
            names.add(result.getProducedBy());
        }
        return names.size();
    }

    private static String statusOf(EventOutcome outcome) {
        return outcome.findStatus().map(EventStatus::name).orElse("(none)");
    }

    private static int distinctReferences(List<NetworkEvent> batch) {
        return (int) batch.stream().map(NetworkEvent::getEventReference).distinct().count();
    }

    private static boolean allMatch(List<NetworkEvent> batch, String pattern) {
        return batch.stream().allMatch(event -> event.getEventReference().matches(pattern));
    }

    /**
     * Whether a node name says which region it is in, which is what makes
     * the document's {@code MUM-RAN-045} readable at a glance.
     */
    private static boolean nodesMatchRegion(List<NetworkEvent> batch) {
        for (NetworkEvent event : batch) {
            NetworkEvent expected = NetworkEventSimulator.build("NE-000000", "X", event
                    .getEventType(), event.getRegion());
            if (expected.getSeverity() != event.getSeverity()) {
                return false;
            }
            if (!event.getNetworkNode().contains("-RAN-")) {
                return false;
            }
        }
        return true;
    }

    private static boolean canReach(Runnable work) {
        try {
            work.run();
            return true;
        } catch (RuntimeException thrown) {
            return false;
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

    /** The same, for work that declares checked exceptions. */
    private static String nameOfThrownChecked(CheckedWork work) {
        try {
            work.run();
            return "(nothing thrown)";
        } catch (Exception thrown) {
            return thrown.getClass().getSimpleName();
        }
    }

    private interface CheckedWork {

        void run() throws Exception;
    }

    /**
     * Counts what the monitor announced without writing anything.
     */
    private static final class CountingListener implements TicketEventListener {

        private final AtomicInteger total = new AtomicInteger();
        private final AtomicInteger breaches = new AtomicInteger();

        @Override
        public String getName() {
            return "verification-counter";
        }

        @Override
        public void onTicketEvent(TicketEvent event) {
            total.incrementAndGet();
            if (event.getType() == TicketEventType.SLA_BREACHED) {
                breaches.incrementAndGet();
            }
        }
    }

    /**
     * Stands in for a notification gateway that is down.
     */
    private static final class FailingListener implements TicketEventListener {

        @Override
        public String getName() {
            return "verification-broken-gateway";
        }

        @Override
        public void onTicketEvent(TicketEvent event) {
            throw new IllegalStateException("The notification gateway is down");
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
        System.out.println(String.format("    [%s] %-48s expected=%-30s actual=%s",
                passed ? "PASS" : "FAIL", label, expected, actual));
    }
}
