package com.amdocs.telecom.service.impl;

import com.amdocs.telecom.dao.AuditLogDAO;
import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.ReportDAO;
import com.amdocs.telecom.dao.SLAConfigurationDAO;
import com.amdocs.telecom.dao.TransactionTemplate;
import com.amdocs.telecom.dao.TroubleTicketDAO;
import com.amdocs.telecom.dto.SlaComplianceDTO;
import com.amdocs.telecom.exception.BusinessException;
import com.amdocs.telecom.exception.ErrorCode;
import com.amdocs.telecom.exception.ResourceNotFoundException;
import com.amdocs.telecom.exception.ValidationException;
import com.amdocs.telecom.model.AuditLog;
import com.amdocs.telecom.model.SLAConfiguration;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.security.AccessControl;
import com.amdocs.telecom.security.Permission;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.SlaService;
import com.amdocs.telecom.service.sla.SlaClock;
import com.amdocs.telecom.service.sla.SlaClockRegistry;
import com.amdocs.telecom.service.sla.SlaEvaluation;
import com.amdocs.telecom.util.AppConstants;
import com.amdocs.telecom.util.AppLogger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The SLA engine.
 *
 * <p>The configured windows are cached, because every evaluation needs them
 * and they change perhaps twice a year. The cache is a single immutable map
 * behind a {@code volatile} field: a reader either sees the whole old map or
 * the whole new one, never a half rebuilt one, which matters because the
 * background monitor reads it on another thread.</p>
 */
public final class SlaServiceImpl implements SlaService {

    /** Refuses a window longer than a month, which is almost always a typo. */
    private static final int MAX_WINDOW_MINUTES = 60 * 24 * 30;

    private final SLAConfigurationDAO configurations;
    private final TroubleTicketDAO tickets;
    private final AuditLogDAO auditLog;
    private final ReportDAO reports;
    private final SlaClockRegistry clocks;

    private volatile Map<Priority, SLAConfiguration> cachedWindows;

    public SlaServiceImpl() {
        this(DAOFactory.getInstance(), SlaClockRegistry.getInstance());
    }

    public SlaServiceImpl(DAOFactory factory, SlaClockRegistry clocks) {
        this.configurations = factory.getSLAConfigurationDAO();
        this.tickets = factory.getTroubleTicketDAO();
        this.auditLog = factory.getAuditLogDAO();
        this.reports = factory.getReportDAO();
        this.clocks = clocks;
    }

    /* ---------- Configured windows ---------- */

    @Override
    public SLAConfiguration configurationFor(Priority priority) {
        if (priority == null) {
            throw new ValidationException("A priority is required to look up an SLA window");
        }
        SLAConfiguration configuration = windows().get(priority);
        if (configuration == null) {
            // Inventing a window would mean inventing a promise, so this is
            // a hard failure rather than a default.
            throw new BusinessException(ErrorCode.SLA_CONFIG_MISSING,
                    "No active SLA configuration exists for " + priority.getDisplayName()
                            + " priority. Add a row to sla_configuration.");
        }
        return configuration;
    }

    @Override
    public Map<Priority, SLAConfiguration> configurations() {
        return windows();
    }

    @Override
    public void reload() {
        cachedWindows = null;
        AppLogger.info(SlaServiceImpl.class, "SLA windows will be reloaded from the table");
    }

    /**
     * Double checked against a local copy so the field is read once, which
     * avoids a reload between the null test and the return.
     */
    private Map<Priority, SLAConfiguration> windows() {
        Map<Priority, SLAConfiguration> loaded = cachedWindows;
        if (loaded == null) {
            // Copied key by key rather than through the EnumMap copy
            // constructor, which rejects an empty source map.
            Map<Priority, SLAConfiguration> copy =
                    new EnumMap<Priority, SLAConfiguration>(Priority.class);
            copy.putAll(configurations.loadAsMap());
            loaded = Collections.unmodifiableMap(copy);
            cachedWindows = loaded;
            AppLogger.debug(SlaServiceImpl.class,
                    "Loaded " + loaded.size() + " SLA window(s) from sla_configuration");
        }
        return loaded;
    }

    @Override
    public SlaClock clockFor(Priority priority) {
        return clocks.clockFor(priority);
    }

    @Override
    public String describeBands() {
        StringBuilder builder = new StringBuilder();
        builder.append(String.format("  %-10s %-12s %-12s %-9s %s",
                "Priority", "Response", "Resolution", "At risk", "Clock"));
        builder.append(System.lineSeparator());
        builder.append("  ").append(AppConstants.LINE_SINGLE);

        Priority[] bands = Priority.values();
        for (int index = bands.length - 1; index >= 0; index--) {
            Priority band = bands[index];
            SLAConfiguration configuration = windows().get(band);
            builder.append(System.lineSeparator());
            if (configuration == null) {
                builder.append(String.format("  %-10s %s", band.getDisplayName(),
                        "(no active configuration)"));
            } else {
                builder.append(String.format("  %-10s %-12s %-12s %-9s %s",
                        band.getDisplayName(),
                        configuration.describeResponseWindow(),
                        configuration.describeResolutionWindow(),
                        configuration.getAtRiskThresholdPercent() + "%",
                        clocks.clockFor(band).getName()));
            }
        }
        return builder.toString();
    }

    /* ---------- Deadlines ---------- */

    @Override
    public LocalDateTime responseDeadlineFor(Priority priority, LocalDateTime raisedAt) {
        return deadline(priority, raisedAt, configurationFor(priority).getResponseMinutes());
    }

    @Override
    public LocalDateTime resolutionDeadlineFor(Priority priority, LocalDateTime raisedAt) {
        return deadline(priority, raisedAt, configurationFor(priority).getResolutionMinutes());
    }

    private LocalDateTime deadline(Priority priority, LocalDateTime raisedAt, int minutes) {
        if (raisedAt == null) {
            throw new ValidationException("The moment the ticket was raised is required");
        }
        return clocks.clockFor(priority).deadlineFrom(raisedAt, minutes);
    }

    @Override
    public void stampDeadlines(TroubleTicket ticket) {
        if (ticket == null) {
            throw new ValidationException("A ticket is required");
        }
        if (ticket.getPriority() == null) {
            throw new ValidationException(
                    "A ticket needs a priority before its SLA deadlines can be set");
        }

        // Settled here rather than left to the database, so the deadlines and
        // the date they were derived from are guaranteed to be consistent.
        LocalDateTime raisedAt = ticket.getCreatedDate() == null
                ? LocalDateTime.now()
                : ticket.getCreatedDate();
        ticket.setCreatedDate(raisedAt);

        SLAConfiguration configuration = configurationFor(ticket.getPriority());
        SlaClock clock = clocks.clockFor(ticket.getPriority());
        ticket.setSlaResponseDeadline(
                clock.deadlineFrom(raisedAt, configuration.getResponseMinutes()));
        ticket.setSlaDeadline(
                clock.deadlineFrom(raisedAt, configuration.getResolutionMinutes()));
    }

    @Override
    public boolean recalculateDeadlines(Long ticketId) {
        TroubleTicket ticket = requireTicket(ticketId);
        if (ticket.isTerminal() || ticket.getResolutionDate() != null) {
            return false;
        }
        LocalDateTime raisedAt = ticket.getCreatedDate();
        if (raisedAt == null) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Ticket " + ticket.getTicketNumber() + " has no raise date, so its SLA "
                            + "deadlines cannot be recomputed");
        }

        SLAConfiguration configuration = configurationFor(ticket.getPriority());
        SlaClock clock = clocks.clockFor(ticket.getPriority());
        boolean changed = tickets.updateDeadlines(ticketId,
                clock.deadlineFrom(raisedAt, configuration.getResponseMinutes()),
                clock.deadlineFrom(raisedAt, configuration.getResolutionMinutes()));
        if (changed) {
            AppLogger.info(SlaServiceImpl.class, "Recomputed SLA deadlines for "
                    + ticket.getTicketNumber() + " at " + ticket.getPriority() + " priority");
        }
        return changed;
    }

    /* ---------- Standing ---------- */

    @Override
    public SlaEvaluation evaluate(TroubleTicket ticket) {
        if (ticket == null) {
            throw new ValidationException("A ticket is required");
        }
        return evaluate(ticket, LocalDateTime.now());
    }

    private SlaEvaluation evaluate(TroubleTicket ticket, LocalDateTime at) {
        return SlaEvaluation.of(ticket, configurationFor(ticket.getPriority()),
                clocks.clockFor(ticket.getPriority()), at);
    }

    @Override
    public SlaEvaluation evaluateByTicketNumber(String ticketNumber) {
        TroubleTicket ticket = tickets.findByTicketNumber(ticketNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No ticket exists with number " + ticketNumber));
        return evaluate(ticket);
    }

    /**
     * One moment is used for the whole sweep, so every ticket in the returned
     * list was judged against the same clock reading.
     */
    @Override
    public List<SlaEvaluation> evaluateOpen() {
        LocalDateTime at = LocalDateTime.now();
        return tickets.findOpen().stream()
                .map(ticket -> evaluate(ticket, at))
                .collect(Collectors.toList());
    }

    @Override
    public List<SlaEvaluation> breached() {
        return evaluateOpen().stream()
                .filter(SlaEvaluation::isBreached)
                .sorted(byMostOverdue())
                .collect(Collectors.toList());
    }

    @Override
    public List<SlaEvaluation> atRisk() {
        return evaluateOpen().stream()
                .filter(SlaEvaluation::isAtRisk)
                .sorted(byMostConsumed())
                .collect(Collectors.toList());
    }

    @Override
    public List<SlaEvaluation> dueWithin(int minutes) {
        if (minutes < 0) {
            throw new ValidationException("A window of minutes cannot be negative");
        }
        LocalDateTime at = LocalDateTime.now();
        // Filtered in SQL so a large ticket table is not read into memory
        // only to discard most of it.
        return tickets.findDueBefore(at.plusMinutes(minutes)).stream()
                .map(ticket -> evaluate(ticket, at))
                .sorted(byMostOverdue())
                .collect(Collectors.toList());
    }

    @Override
    public List<SlaEvaluation> needingAttention() {
        return evaluateOpen().stream()
                .filter(SlaEvaluation::needsAttention)
                .sorted(byMostOverdue())
                .collect(Collectors.toList());
    }

    @Override
    public int refreshStoredStatuses() {
        List<SlaEvaluation> stale = evaluateOpen().stream()
                .filter(SlaEvaluation::isStale)
                .collect(Collectors.toList());
        if (stale.isEmpty()) {
            return 0;
        }

        // One transaction for the sweep: a monitor pass either lands or it
        // does not, so a dashboard never reads a half updated set.
        int corrected = TransactionTemplate.execute(context -> {
            int written = 0;
            for (SlaEvaluation evaluation : stale) {
                if (tickets.updateSlaStatus(evaluation.getTicketId(), evaluation.getLiveStatus())) {
                    written++;
                }
            }
            return written;
        });

        if (corrected > 0) {
            AppLogger.info(SlaServiceImpl.class,
                    "SLA monitor corrected sla_status on " + corrected + " ticket(s)");
        }
        return corrected;
    }

    /* ---------- Orderings ---------- */

    /**
     * Soonest deadline first, so the most overdue ticket leads. Java 8 cannot
     * infer the element type through a chained {@code reversed()} or
     * {@code thenComparing}, which is why the comparator is built in named
     * steps.
     */
    private static Comparator<SlaEvaluation> byMostOverdue() {
        Comparator<SlaEvaluation> byDeadline = Comparator.comparing(
                SlaEvaluation::getResolutionDeadline,
                Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()));
        Comparator<SlaEvaluation> byUrgency = Comparator.comparingInt(
                evaluation -> evaluation.getPriority() == null
                        ? 0 : evaluation.getPriority().getWeight());
        return byDeadline.thenComparing(byUrgency.reversed());
    }

    private static Comparator<SlaEvaluation> byMostConsumed() {
        Comparator<SlaEvaluation> byConsumed =
                Comparator.comparingDouble(SlaEvaluation::getConsumedFraction);
        return byConsumed.reversed();
    }

    /* ---------- Reporting ---------- */

    @Override
    public List<SlaComplianceDTO> compliance() {
        return reports.findSlaCompliance();
    }

    /* ---------- Administration ---------- */

    @Override
    public void retuneWindows(UserSession actor, Priority priority,
                              int responseMinutes, int resolutionMinutes) {
        AccessControl.require(actor, Permission.MANAGE_SLA_CONFIGURATION);
        if (priority == null) {
            throw new ValidationException("A priority band is required");
        }
        validateWindows(responseMinutes, resolutionMinutes);

        SLAConfiguration existing = configurationFor(priority);
        String before = existing.getResponseMinutes() + "/" + existing.getResolutionMinutes();
        String after = responseMinutes + "/" + resolutionMinutes;
        if (before.equals(after)) {
            throw new ValidationException("The " + priority.getDisplayName()
                    + " band already uses those windows");
        }

        String performedBy = actor.getUsername();
        boolean applied = TransactionTemplate.execute(context -> {
            if (!configurations.updateWindows(priority, responseMinutes, resolutionMinutes)) {
                return Boolean.FALSE;
            }
            auditLog.insert(new AuditLog("SLA_CONFIGURATION", priority.name(),
                    "SLA_WINDOWS_CHANGED", performedBy)
                    .withChange(before, after)
                    .withDetails("Response and resolution minutes for the "
                            + priority.getDisplayName() + " band"));
            return Boolean.TRUE;
        });

        if (!applied) {
            throw new BusinessException(ErrorCode.SLA_CONFIG_MISSING,
                    "No SLA configuration row was updated for " + priority.getDisplayName());
        }

        // Only after the write has committed, so a failed retune cannot leave
        // the cache describing windows the table does not hold.
        reload();
        AppLogger.info(SlaServiceImpl.class, "SLA windows for " + priority
                + " changed from " + before + " to " + after + " by " + performedBy);
    }

    private static void validateWindows(int responseMinutes, int resolutionMinutes) {
        List<String> problems = new ArrayList<String>();
        if (responseMinutes <= 0) {
            problems.add("The response window must be at least one minute");
        }
        if (resolutionMinutes <= 0) {
            problems.add("The resolution window must be at least one minute");
        }
        if (responseMinutes > MAX_WINDOW_MINUTES || resolutionMinutes > MAX_WINDOW_MINUTES) {
            problems.add("A window longer than 30 days is almost certainly a mistake");
        }
        if (responseMinutes > 0 && resolutionMinutes > 0 && resolutionMinutes < responseMinutes) {
            problems.add("Resolution cannot be due before the first response");
        }
        if (!problems.isEmpty()) {
            throw new ValidationException("The SLA windows are not usable", problems);
        }
    }

    private TroubleTicket requireTicket(Long ticketId) {
        if (ticketId == null) {
            throw new ValidationException("A ticket identifier is required");
        }
        Optional<TroubleTicket> found = tickets.findById(ticketId);
        return found.orElseThrow(() -> new ResourceNotFoundException(
                "No ticket exists with identifier " + ticketId));
    }
}
