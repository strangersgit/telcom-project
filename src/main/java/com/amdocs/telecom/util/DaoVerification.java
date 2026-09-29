package com.amdocs.telecom.util;

import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.NetworkEngineerDAO;
import com.amdocs.telecom.dao.NetworkEventDAO;
import com.amdocs.telecom.dao.ReportDAO;
import com.amdocs.telecom.dao.TransactionTemplate;
import com.amdocs.telecom.dto.CategoryIncidentDTO;
import com.amdocs.telecom.dto.DashboardStatsDTO;
import com.amdocs.telecom.dto.EngineerRecommendationDTO;
import com.amdocs.telecom.dto.EngineerWorkloadDTO;
import com.amdocs.telecom.dto.OpenTicketDTO;
import com.amdocs.telecom.dto.RepeatIncidentDTO;
import com.amdocs.telecom.dto.SlaComplianceDTO;
import com.amdocs.telecom.dto.TicketDetailDTO;
import com.amdocs.telecom.exception.DataAccessException;
import com.amdocs.telecom.exception.DuplicateResourceException;
import com.amdocs.telecom.exception.ResourceNotFoundException;
import com.amdocs.telecom.model.AuditLog;
import com.amdocs.telecom.model.Customer;
import com.amdocs.telecom.model.NetworkEngineer;
import com.amdocs.telecom.model.NetworkEvent;
import com.amdocs.telecom.model.SLAConfiguration;
import com.amdocs.telecom.model.TelecomService;
import com.amdocs.telecom.model.TicketStatusHistory;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.UserAccount;
import com.amdocs.telecom.model.enums.EventStatus;
import com.amdocs.telecom.model.enums.NetworkEventType;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.model.enums.Severity;
import com.amdocs.telecom.model.enums.Specialization;
import com.amdocs.telecom.model.enums.TicketStatus;

import java.sql.Savepoint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Exercises the whole data access layer against the seeded database.
 *
 * <p>Compiling proves nothing about SQL, so this walks every DAO, both
 * paths through the transaction template, the guarded updates and the views
 * and procedures, and reports what each one actually did.</p>
 *
 * <p>Everything it writes is removed again before it returns, so the seeded
 * data is exactly as it was. Rows are created only in {@code network_events},
 * which nothing else depends on.</p>
 */
public final class DaoVerification {

    private DaoVerification() {
        throw new AssertionError("DaoVerification is not instantiable");
    }

    /** Prefix for the throwaway rows, so anything left behind is obvious. */
    private static final String PROBE_PREFIX = "NE-VFY";

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

        System.out.println("  Data access layer verification");
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println();

        try {
            verifyFactory(factory);
            verifyReadPaths(factory);
            verifyNullAndEnumMapping(factory);
            verifyViews(factory);
            verifyStoredProcedure(factory);
            verifyTransactionCommit(factory);
            verifyTransactionRollback(factory);
            verifySavepoint(factory);
            verifyGuardedUpdates(factory);
            verifyAppendOnlyRefusal(factory);
            verifyDuplicateDetection(factory);
            verifyBatchInsert(factory);
        } catch (RuntimeException failure) {
            checksFailed++;
            System.out.println("  Verification aborted: " + failure);
            AppLogger.error(DaoVerification.class, "DAO verification aborted", failure);
        } finally {
            int removed = removeProbeRows(factory.getNetworkEventDAO());
            if (removed > 0) {
                System.out.println();
                System.out.println("  Cleaned up " + removed + " temporary row(s)");
            }
        }

        System.out.println();
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println("  " + (checksRun - checksFailed) + " of " + checksRun + " checks passed");
        System.out.println();

        if (checksFailed == 0) {
            System.out.println("  The data access layer is working against the live database.");
            AppLogger.info(DaoVerification.class, "DAO verification passed " + checksRun + " checks");
        } else {
            System.out.println("  " + checksFailed + " check(s) failed. See the detail above.");
            AppLogger.warn(DaoVerification.class, "DAO verification had " + checksFailed + " failure(s)");
        }
        System.out.println();
        return checksFailed == 0 ? 0 : 1;
    }

    /* ---------- 1. Factory ---------- */

    private static void verifyFactory(DAOFactory factory) {
        section("1. Factory wiring");
        check("vendor", "MYSQL", factory.vendor().name());
        check("same instance returned twice", "true",
                String.valueOf(DAOFactory.getInstance() == DAOFactory.getInstance()));

        Object[] daos = {
                factory.getUserDAO(), factory.getCustomerDAO(), factory.getTelecomServiceDAO(),
                factory.getNetworkEngineerDAO(), factory.getSLAConfigurationDAO(),
                factory.getTroubleTicketDAO(), factory.getTicketStatusHistoryDAO(),
                factory.getEscalationHistoryDAO(), factory.getNetworkEventDAO(),
                factory.getNotificationDAO(), factory.getFeedbackDAO(), factory.getAuditLogDAO(),
                factory.getLoginHistoryDAO(), factory.getReportDAO()
        };
        int supplied = 0;
        for (Object dao : daos) {
            if (dao != null) {
                supplied++;
            }
        }
        check("all DAOs supplied", "14", String.valueOf(supplied));
    }

    /* ---------- 2. Reads ---------- */

    private static void verifyReadPaths(DAOFactory factory) {
        section("2. Reading the seeded data");

        check("users", "17", String.valueOf(factory.getUserDAO().count()));
        check("customers", "8", String.valueOf(factory.getCustomerDAO().count()));
        check("services", "14", String.valueOf(factory.getTelecomServiceDAO().count()));
        check("engineers", "6", String.valueOf(factory.getNetworkEngineerDAO().count()));
        check("sla bands", "4", String.valueOf(factory.getSLAConfigurationDAO().count()));
        check("tickets", "15", String.valueOf(factory.getTroubleTicketDAO().count()));

        Optional<Customer> zenith = factory.getCustomerDAO().findByCustomerNumber("CUST100245");
        check("findByCustomerNumber", "true", String.valueOf(zenith.isPresent()));
        check("  customer name", "Zenith Logistics Pvt Ltd",
                zenith.map(Customer::getCustomerName).orElse("(missing)"));
        check("  enum mapped", "ENTERPRISE",
                zenith.map(customer -> customer.getCustomerType().name()).orElse("(missing)"));

        Optional<NetworkEngineer> arun =
                factory.getNetworkEngineerDAO().findByEmployeeCode("ENG1008");
        check("findByEmployeeCode", "Arun Menon",
                arun.map(NetworkEngineer::getEngineerName).orElse("(missing)"));
        check("  specialization", "CORE_NETWORK",
                arun.map(engineer -> engineer.getSpecialization().name()).orElse("(missing)"));

        Optional<TroubleTicket> ticket =
                factory.getTroubleTicketDAO().findByTicketNumber("TT-2026-004521");
        check("findByTicketNumber", "true", String.valueOf(ticket.isPresent()));
        check("  deadline read back", "true",
                String.valueOf(ticket.isPresent() && ticket.get().getSlaDeadline() != null));

        Optional<UserAccount> user = factory.getUserDAO().findByUsername("sdesk1");
        check("findByUsername", "SERVICE_DESK",
                user.map(account -> account.getRole().name()).orElse("(missing)"));
        check("  credential read back", "true",
                String.valueOf(user.isPresent() && user.get().hasUsablePassword()));
        check("unknown username is empty", "false",
                String.valueOf(factory.getUserDAO().findByUsername("no-such-user").isPresent()));

        // Zero once --provision-users has run. A non-zero count here means
        // provisioning is still outstanding rather than that the DAO is wrong.
        check("none awaiting a password", "0",
                String.valueOf(factory.getUserDAO().findAwaitingPassword().size()));

        Map<Priority, SLAConfiguration> windows = factory.getSLAConfigurationDAO().loadAsMap();
        check("SLA map keyed by priority", "4", String.valueOf(windows.size()));
        check("  CRITICAL response minutes", "15",
                String.valueOf(windows.get(Priority.CRITICAL).getResponseMinutes()));
        check("  CRITICAL resolution minutes", "120",
                String.valueOf(windows.get(Priority.CRITICAL).getResolutionMinutes()));
        check("  LOW resolution minutes", "2880",
                String.valueOf(windows.get(Priority.LOW).getResolutionMinutes()));

        long serviceCount = zenith.isPresent()
                ? factory.getTelecomServiceDAO().findByCustomerId(zenith.get().getId()).size() : -1;
        check("services for one customer", "true", String.valueOf(serviceCount > 0));

        check("getById on a missing row throws", "ResourceNotFoundException",
                nameOfThrown(() -> factory.getCustomerDAO().getById(999999L)));
    }

    /* ---------- 3. Null and enum handling ---------- */

    private static void verifyNullAndEnumMapping(DAOFactory factory) {
        section("3. Null and enum handling");

        List<TroubleTicket> unassigned = factory.getTroubleTicketDAO().findUnassigned();
        check("unassigned tickets found", "true", String.valueOf(!unassigned.isEmpty()));
        if (!unassigned.isEmpty()) {
            TroubleTicket first = unassigned.get(0);
            check("  engineer id is null not zero", "true",
                    String.valueOf(first.getAssignedEngineerId() == null));
            check("  Optional reports absent", "false",
                    String.valueOf(first.findAssignedEngineerId().isPresent()));
        }

        List<TroubleTicket> open = factory.getTroubleTicketDAO().findOpen();
        boolean orderedByUrgency = true;
        for (int index = 1; index < open.size(); index++) {
            int previous = open.get(index - 1).getPriority().getWeight();
            int current = open.get(index).getPriority().getWeight();
            if (current > previous) {
                orderedByUrgency = false;
                break;
            }
        }
        check("open queue ordered by urgency", "true", String.valueOf(orderedByUrgency));

        int highest = factory.getTroubleTicketDAO().findHighestSequenceForYear(2026);
        check("ticket sequence read from data", "true", String.valueOf(highest >= 4521));

        List<TicketStatusHistory> trail = factory.getTroubleTicketDAO()
                .findByTicketNumber("TT-2026-004521")
                .map(found -> factory.getTicketStatusHistoryDAO().findByTicketId(found.getId()))
                .orElse(new ArrayList<TicketStatusHistory>());
        check("status trail present", "true", String.valueOf(!trail.isEmpty()));
        check("  first entry has no old status", "true",
                String.valueOf(!trail.isEmpty() && trail.get(0).isCreationEntry()));
    }

    /* ---------- 4. Views ---------- */

    private static void verifyViews(DAOFactory factory) {
        section("4. Views through ReportDAO");
        ReportDAO reports = factory.getReportDAO();

        Optional<TicketDetailDTO> detail = reports.findTicketDetail("TT-2026-004521");
        check("vw_ticket_details", "true", String.valueOf(detail.isPresent()));
        if (detail.isPresent()) {
            TicketDetailDTO card = detail.get();
            check("  customer joined", "CUST100245", String.valueOf(card.getCustomerNumber()));
            check("  service joined", "true", String.valueOf(card.getServiceName() != null));
            check("  live SLA status computed", "true", String.valueOf(card.getLiveSlaStatus() != null));
        }

        List<OpenTicketDTO> queue = reports.findOpenTickets();
        check("vw_open_tickets", "9", String.valueOf(queue.size()));
        check("  most urgent first", "CRITICAL",
                queue.isEmpty() ? "(empty)" : queue.get(0).getPriority().name());

        List<OpenTicketDTO> attention = reports.findTicketsNeedingAttention();
        check("at risk or breached", "true", String.valueOf(!attention.isEmpty()));

        List<EngineerWorkloadDTO> workload = reports.findEngineerWorkload();
        check("vw_engineer_workload", "6", String.valueOf(workload.size()));

        List<SlaComplianceDTO> compliance = reports.findSlaCompliance();
        check("vw_sla_compliance", "4", String.valueOf(compliance.size()));
        check("  tightest band first", "CRITICAL",
                compliance.isEmpty() ? "(empty)" : compliance.get(0).getPriority().name());

        List<CategoryIncidentDTO> categories = reports.findCategoryIncidents();
        check("vw_category_incidents", "true", String.valueOf(!categories.isEmpty()));

        List<RepeatIncidentDTO> repeats = reports.findRepeatIncidents();
        check("vw_customer_repeat_incidents", "true", String.valueOf(!repeats.isEmpty()));
        check("  HAVING filtered singles out", "true",
                String.valueOf(repeats.stream().allMatch(row -> row.getIncidentCount() > 1)));

        // The aggregate view is cross-checked against the row level views and
        // the table count rather than against fixed numbers. A swapped column
        // in the mapper still shows up, and the breach count is free to grow
        // as real time passes the seeded deadlines.
        DashboardStatsDTO stats = reports.findDashboardStats();
        check("vw_manager_dashboard open", String.valueOf(queue.size()),
                String.valueOf(stats.getTotalOpenTickets()));

        long criticalInQueue = queue.stream()
                .filter(row -> row.getPriority() == Priority.CRITICAL).count();
        check("  critical agrees with the queue", String.valueOf(criticalInQueue),
                String.valueOf(stats.getCriticalIncidents()));

        long breachedInQueue = queue.stream().filter(OpenTicketDTO::isBreached).count();
        checkAtLeast("  breached covers the open queue", breachedInQueue, stats.getSlaBreached());
        checkAtMost("  breached within the total", stats.getTotalTickets(), stats.getSlaBreached());

        check("  total tickets", String.valueOf(factory.getTroubleTicketDAO().count()),
                String.valueOf(stats.getTotalTickets()));

        List<Object[]> volume = reports.ticketVolumeReport(
                LocalDate.now().minusDays(30), LocalDate.now());
        check("sp_ticket_volume_report", "true", String.valueOf(!volume.isEmpty()));
    }

    /* ---------- 5. Stored procedure ---------- */

    private static void verifyStoredProcedure(DAOFactory factory) {
        section("5. Stored procedure and its Java twin");

        List<EngineerRecommendationDTO> viaProcedure = factory.getReportDAO()
                .recommendEngineers(Specialization.BROADBAND, Region.SOUTH, 5);
        check("sp_recommend_engineers", "1", String.valueOf(viaProcedure.size()));
        check("  picked", "ENG1021",
                viaProcedure.isEmpty() ? "(none)" : viaProcedure.get(0).getEmployeeCode());

        List<NetworkEngineer> viaDao = factory.getNetworkEngineerDAO()
                .findAvailableFor(Specialization.BROADBAND, Region.SOUTH);
        check("DAO agrees with procedure", "1", String.valueOf(viaDao.size()));
        check("  same engineer", "ENG1021",
                viaDao.isEmpty() ? "(none)" : viaDao.get(0).getEmployeeCode());

        // The only core network engineer is in the WEST, so asking for the
        // SOUTH must find nobody while a null region must still find him.
        // That is what proves the "? IS NULL OR region = ?" branch works.
        List<NetworkEngineer> wrongRegion = factory.getNetworkEngineerDAO()
                .findAvailableFor(Specialization.CORE_NETWORK, Region.SOUTH);
        check("region narrows the search", "0", String.valueOf(wrongRegion.size()));

        List<NetworkEngineer> nationwide = factory.getNetworkEngineerDAO()
                .findAvailableFor(Specialization.CORE_NETWORK, null);
        checkAtLeast("null region widens the search", wrongRegion.size() + 1, nationwide.size());
        boolean sortedByWorkload = true;
        for (int index = 1; index < nationwide.size(); index++) {
            if (nationwide.get(index).getActiveTicketCount()
                    < nationwide.get(index - 1).getActiveTicketCount()) {
                sortedByWorkload = false;
                break;
            }
        }
        check("  lightest workload first", "true", String.valueOf(sortedByWorkload));

        List<NetworkEngineer> onLeaveExcluded = factory.getNetworkEngineerDAO()
                .findAvailableFor(Specialization.TRANSMISSION, Region.CENTRAL);
        check("on-leave engineer excluded", "0", String.valueOf(onLeaveExcluded.size()));
    }

    /* ---------- 6. Transactions ---------- */

    private static void verifyTransactionCommit(DAOFactory factory) {
        section("6. Transaction commits");
        NetworkEventDAO events = factory.getNetworkEventDAO();
        String reference = probeReference("COMMIT");

        Long id = TransactionTemplate.execute(context ->
                events.insert(probeEvent(reference)).getId());

        check("insert inside a transaction", "true", String.valueOf(id != null));
        check("visible after commit", "true",
                String.valueOf(events.findByReference(reference).isPresent()));

        boolean deleted = events.deleteById(id);
        check("cleaned up", "true", String.valueOf(deleted));
    }

    private static void verifyTransactionRollback(DAOFactory factory) {
        section("7. Transaction rolls back on failure");
        NetworkEventDAO events = factory.getNetworkEventDAO();
        String reference = probeReference("ROLLBACK");

        String thrown = nameOfThrown(() -> TransactionTemplate.run(context -> {
            events.insert(probeEvent(reference));
            throw new IllegalStateException("deliberate failure after the insert");
        }));

        check("failure propagates", "DataAccessException", thrown);
        check("insert was undone", "false",
                String.valueOf(events.findByReference(reference).isPresent()));
    }

    private static void verifySavepoint(DAOFactory factory) {
        section("8. Savepoint rewinds part of a transaction");
        NetworkEventDAO events = factory.getNetworkEventDAO();
        String keep = probeReference("KEEP");
        String discard = probeReference("DISCARD");

        Long keptId = TransactionTemplate.execute(context -> {
            Long firstId = events.insert(probeEvent(keep)).getId();

            Savepoint marker = context.savepoint("before_optional_work");
            events.insert(probeEvent(discard));
            context.rollbackTo(marker);

            return firstId;
        });

        check("work before the savepoint survives", "true",
                String.valueOf(events.findByReference(keep).isPresent()));
        check("work after it is discarded", "false",
                String.valueOf(events.findByReference(discard).isPresent()));

        events.deleteById(keptId);
    }

    /* ---------- 9. Guarded updates ---------- */

    private static void verifyGuardedUpdates(DAOFactory factory) {
        section("9. Guarded updates refuse stale writes");

        Optional<TroubleTicket> assigned = factory.getTroubleTicketDAO()
                .findByStatus(TicketStatus.IN_PROGRESS).stream().findFirst();
        check("an in-progress ticket exists", "true", String.valueOf(assigned.isPresent()));

        if (assigned.isPresent()) {
            boolean reassigned = factory.getTroubleTicketDAO()
                    .assignEngineer(assigned.get().getId(), 1L, TicketStatus.OPEN);
            check("assign with wrong expected status", "false", String.valueOf(reassigned));
            check("  ticket untouched", assigned.get().getAssignedEngineerId().toString(),
                    String.valueOf(factory.getTroubleTicketDAO()
                            .findById(assigned.get().getId()).get().getAssignedEngineerId()));
        }

        factory.getNetworkEngineerDAO().findByEmployeeCode("ENG1030")
                .ifPresent(engineer -> verifyCapacityGuard(factory.getNetworkEngineerDAO(), engineer));

        Optional<NetworkEvent> alreadyHandled = factory.getNetworkEventDAO()
                .findByStatus(EventStatus.TICKET_CREATED).stream().findFirst();
        if (alreadyHandled.isPresent()) {
            boolean claimed = factory.getNetworkEventDAO()
                    .claimForProcessing(alreadyHandled.get().getId(), EventStatus.RECEIVED);
            check("claiming a handled event", "false", String.valueOf(claimed));
        } else {
            check("claiming a handled event", "skipped", "skipped");
        }
    }

    /**
     * Fills an engineer to capacity to prove the WHERE clause stops the
     * counter there, then rewinds to a savepoint so the seeded row is left
     * exactly as it was found.
     */
    private static void verifyCapacityGuard(NetworkEngineerDAO engineers, NetworkEngineer engineer) {
        final Long engineerId = engineer.getId();
        final int startingWorkload = engineer.getActiveTicketCount();
        final int spare = engineer.getSpareCapacity();

        int accepted = TransactionTemplate.execute(context -> {
            Savepoint marker = context.savepoint("before_capacity_probe");
            int increments = 0;
            while (engineers.incrementWorkload(engineerId)) {
                increments++;
            }
            context.rollbackTo(marker);
            return increments;
        });

        check("increments stop at capacity", String.valueOf(spare), String.valueOf(accepted));
        check("  workload restored by the rollback", String.valueOf(startingWorkload),
                String.valueOf(engineers.getById(engineerId).getActiveTicketCount()));

        if (startingWorkload == 0) {
            check("  decrement refused at zero", "false",
                    String.valueOf(engineers.decrementWorkload(engineerId)));
        }
    }

    /* ---------- 10. Append only ---------- */

    private static void verifyAppendOnlyRefusal(DAOFactory factory) {
        section("10. History cannot be rewritten");

        AuditLog entry = factory.getAuditLogDAO().findRecent(1).stream().findFirst().orElse(null);
        check("audit rows readable", "true", String.valueOf(entry != null));

        // Looked up by actor rather than taken from the top of the trail.
        // Once the application starts writing its own entries the newest row
        // is no longer the trigger's, but the trigger's rows are still there.
        check("the database trigger wrote entries", "true",
                String.valueOf(!factory.getAuditLogDAO().findByUser("DB_TRIGGER", 1).isEmpty()));

        if (entry != null) {
            check("update is refused", "DataAccessException",
                    nameOfThrown(() -> factory.getAuditLogDAO().update(entry)));
            check("delete is refused", "DataAccessException",
                    nameOfThrown(() -> factory.getAuditLogDAO().deleteById(entry.getId())));
        }
    }

    /* ---------- 11. Duplicate detection ---------- */

    private static void verifyDuplicateDetection(DAOFactory factory) {
        section("11. Unique constraints surface as a business error");
        NetworkEventDAO events = factory.getNetworkEventDAO();
        String reference = probeReference("DUP");

        NetworkEvent first = events.insert(probeEvent(reference));
        check("first insert accepted", "true", String.valueOf(first.getId() != null));
        check("second insert rejected", "DuplicateResourceException",
                nameOfThrown(() -> events.insert(probeEvent(reference))));

        events.deleteById(first.getId());
    }

    /* ---------- 12. Batch ---------- */

    private static void verifyBatchInsert(DAOFactory factory) {
        section("12. Batch insert");
        NetworkEventDAO events = factory.getNetworkEventDAO();

        List<NetworkEvent> burst = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            burst.add(probeEvent(probeReference("B" + index)));
        }
        int written = events.insertBatch(burst);
        check("rows written in one round trip", "5", String.valueOf(written));

        long found = 0;
        for (NetworkEvent event : burst) {
            if (events.findByReference(event.getEventReference()).isPresent()) {
                found++;
            }
        }
        check("all readable afterwards", "5", String.valueOf(found));
    }

    /* ---------- Helpers ---------- */

    private static NetworkEvent probeEvent(String reference) {
        NetworkEvent event = new NetworkEvent(reference, "VERIFY-NODE-01",
                NetworkEventType.HEARTBEAT, Severity.INFO, Region.WEST,
                "Temporary row written by the DAO verification run");
        event.setEventStatus(EventStatus.IGNORED);
        return event;
    }

    private static String probeReference(String suffix) {
        String reference = PROBE_PREFIX + "-" + suffix;
        return reference.length() > 20 ? reference.substring(0, 20) : reference;
    }

    /**
     * Removes anything this run created, including rows a failed check may
     * have left behind.
     */
    private static int removeProbeRows(NetworkEventDAO events) {
        int removed = 0;
        for (NetworkEvent event : events.findAll()) {
            String reference = event.getEventReference();
            if (reference != null && reference.startsWith(PROBE_PREFIX)) {
                if (events.deleteById(event.getId())) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Runs the work and names whatever it threw, so a check can assert on
     * the failure rather than on a return value.
     */
    private static String nameOfThrown(Runnable work) {
        try {
            work.run();
            return "(nothing thrown)";
        } catch (DuplicateResourceException expected) {
            return "DuplicateResourceException";
        } catch (ResourceNotFoundException expected) {
            return "ResourceNotFoundException";
        } catch (DataAccessException expected) {
            return "DataAccessException";
        } catch (RuntimeException unexpected) {
            return unexpected.getClass().getSimpleName();
        }
    }

    private static void section(String title) {
        System.out.println("  " + title);
    }

    private static void check(String label, String expected, String actual) {
        report(label, expected, actual, expected.equals(actual));
    }

    /**
     * For numbers that may legitimately grow between the seed being loaded
     * and this run, such as anything the database measures against NOW().
     */
    private static void checkAtLeast(String label, long minimum, long actual) {
        report(label, ">= " + minimum, String.valueOf(actual), actual >= minimum);
    }

    private static void checkAtMost(String label, long maximum, long actual) {
        report(label, "<= " + maximum, String.valueOf(actual), actual <= maximum);
    }

    private static void report(String label, String expected, String actual, boolean passed) {
        checksRun++;
        if (!passed) {
            checksFailed++;
        }
        System.out.println(String.format("    [%s] %-36s expected=%-24s actual=%s",
                passed ? "PASS" : "FAIL", label, expected, actual));
    }
}
