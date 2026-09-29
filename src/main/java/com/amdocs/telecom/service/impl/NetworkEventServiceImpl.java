package com.amdocs.telecom.service.impl;

import com.amdocs.telecom.dao.CustomerDAO;
import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.dao.NetworkEventDAO;
import com.amdocs.telecom.dao.TelecomServiceDAO;
import com.amdocs.telecom.dao.TicketStatusHistoryDAO;
import com.amdocs.telecom.dao.TransactionTemplate;
import com.amdocs.telecom.dao.TroubleTicketDAO;
import com.amdocs.telecom.exception.DuplicateResourceException;
import com.amdocs.telecom.model.Customer;
import com.amdocs.telecom.model.NetworkEvent;
import com.amdocs.telecom.model.TelecomService;
import com.amdocs.telecom.model.TicketStatusHistory;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.enums.CustomerType;
import com.amdocs.telecom.model.enums.EventStatus;
import com.amdocs.telecom.model.enums.IncidentCategory;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.model.enums.SLAStatus;
import com.amdocs.telecom.model.enums.Severity;
import com.amdocs.telecom.model.enums.TicketStatus;
import com.amdocs.telecom.service.NetworkEventService;
import com.amdocs.telecom.service.SlaService;
import com.amdocs.telecom.service.event.EventOutcome;
import com.amdocs.telecom.service.event.NetworkEventSimulator;
import com.amdocs.telecom.service.event.TicketEvent;
import com.amdocs.telecom.service.event.TicketEventPublisher;
import com.amdocs.telecom.util.AppLogger;
import com.amdocs.telecom.validation.TicketValidator;
import com.amdocs.telecom.validation.Validators;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Decides what each network alarm is worth, and opens tickets for the ones
 * that warrant it.
 *
 * <h3>Which service an alarm belongs to</h3>
 *
 * <p>An alarm names a network node, and a ticket needs a customer and a
 * service. Nothing in the schema maps nodes to circuits, because the case
 * study's tables describe customer facing services rather than network
 * inventory, and inventing a node table would be inventing a requirement.
 * What an alarm does carry is a region, and a service belongs to a customer
 * who is in one, so region is the link available.</p>
 *
 * <p>That leaves a choice when a region holds many services. The ticket goes
 * to the highest tier customer affected, and among their services to the
 * oldest, because tier is what decides whose promise is tightest and age
 * makes the choice repeatable. A real operator would resolve the node to a
 * circuit and raise a ticket per affected customer; this is the honest
 * approximation the tables support, and it is confined to one method so
 * replacing it means replacing that method.</p>
 *
 * <h3>Why a second alarm does not mean a second ticket</h3>
 *
 * <p>A fibre cut produces one alarm and then fifty more while it is being
 * fixed. Opening fifty tickets would bury the service desk in the same
 * fault, so an alarm whose service and category already have an open
 * automatic ticket is attached to that ticket instead. This is the one piece
 * of correlation here, and it is deliberately narrow: same service, same
 * category, still open, opened automatically. An alarm about a different
 * fault on the same service still gets its own ticket, and a ticket a person
 * raised is never quietly reused.</p>
 *
 * <h3>Why nothing here throws for a bad alarm</h3>
 *
 * <p>These run on consumer threads. A thread that dies on the first awkward
 * alarm stops processing every alarm behind it, so a failure is recorded on
 * the row as {@link EventStatus#FAILED} and reported as an outcome. The
 * consumer stays alive and the operator can see which alarms need looking
 * at.</p>
 */
public final class NetworkEventServiceImpl implements NetworkEventService {

    /** How many pending alarms one drain will take, when not told. */
    private static final int DEFAULT_BATCH = 25;

    /** A hard ceiling, because a drain holds a connection while it runs. */
    public static final int MAX_BATCH = 200;

    private final NetworkEventDAO events;
    private final TroubleTicketDAO tickets;
    private final TelecomServiceDAO services;
    private final CustomerDAO customers;
    private final TicketStatusHistoryDAO history;
    private final SlaService sla;
    private final TicketNumberGenerator numbers;
    private final NetworkEventSimulator simulator;
    private final TicketEventPublisher publisher;

    public NetworkEventServiceImpl() {
        this(DAOFactory.getInstance(), new SlaServiceImpl(), TicketEventPublisher.getInstance(),
                new NetworkEventSimulator());
    }

    public NetworkEventServiceImpl(DAOFactory factory, SlaService sla,
                                   TicketEventPublisher publisher,
                                   NetworkEventSimulator simulator) {
        this.events = factory.getNetworkEventDAO();
        this.tickets = factory.getTroubleTicketDAO();
        this.services = factory.getTelecomServiceDAO();
        this.customers = factory.getCustomerDAO();
        this.history = factory.getTicketStatusHistoryDAO();
        this.numbers = new TicketNumberGenerator(factory.getTroubleTicketDAO());
        if (sla == null || publisher == null || simulator == null) {
            throw new IllegalArgumentException(
                    "An SLA service, an event publisher and a simulator are all required");
        }
        this.sla = sla;
        this.publisher = publisher;
        this.simulator = simulator;
    }

    /* ---------- Taking alarms in ---------- */

    @Override
    public NetworkEvent record(NetworkEvent event) {
        requireAlarm(event);
        return events.insert(event);
    }

    @Override
    public int recordAll(List<NetworkEvent> batch) {
        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        for (NetworkEvent event : batch) {
            requireAlarm(event);
        }
        return events.insertBatch(batch);
    }

    @Override
    public List<NetworkEvent> pending(int limit) {
        return events.findPending(bounded(limit));
    }

    /* ---------- Working them off ---------- */

    @Override
    public EventOutcome process(NetworkEvent event) {
        requireStored(event);
        String reference = event.getEventReference();
        try {
            return TransactionTemplate.execute(context -> decide(event));
        } catch (RuntimeException failure) {
            // The transaction took the claim back with it, so the row is
            // RECEIVED again. Marking it FAILED is a separate write on
            // purpose: the point of the record is to survive the rollback
            // that removed everything else.
            AppLogger.error(NetworkEventServiceImpl.class,
                    "Alarm " + reference + " could not be processed", failure);
            events.markProcessed(event.getId(), EventStatus.FAILED, null);
            return EventOutcome.failed(reference, messageOf(failure));
        }
    }

    /**
     * The decision itself, inside the caller's transaction.
     */
    private EventOutcome decide(NetworkEvent event) {
        String reference = event.getEventReference();

        // Claimed before anything is read, so two consumers holding the same
        // alarm cannot both go on to raise a ticket for it. The loser is told
        // it lost rather than being failed: nothing went wrong.
        if (!events.claimForProcessing(event.getId(), EventStatus.RECEIVED)) {
            return EventOutcome.alreadyClaimed(reference);
        }

        if (!event.warrantsTicket()) {
            events.markProcessed(event.getId(), EventStatus.IGNORED, null);
            return EventOutcome.ignored(reference, notWorthATicket(event));
        }

        Optional<TelecomService> affected = affectedService(event);
        if (!affected.isPresent()) {
            events.markProcessed(event.getId(), EventStatus.IGNORED, null);
            return EventOutcome.ignored(reference, nothingAffected(event));
        }
        TelecomService service = affected.get();
        IncidentCategory category = event.getEventType().getMappedCategory();

        Optional<TroubleTicket> alreadyOpen = openAutomaticTicket(service.getId(), category);
        if (alreadyOpen.isPresent()) {
            TroubleTicket existing = alreadyOpen.get();
            events.markProcessed(event.getId(), EventStatus.TICKET_CREATED, existing.getId());
            AppLogger.info(NetworkEventServiceImpl.class, "Alarm " + reference
                    + " folded into the open ticket " + existing.getTicketNumber());
            return EventOutcome.correlated(reference, existing.getId(),
                    existing.getTicketNumber());
        }

        TroubleTicket raised = raiseTicket(event, service, category);
        events.markProcessed(event.getId(), EventStatus.TICKET_CREATED, raised.getId());
        return EventOutcome.ticketRaised(reference, raised.getId(), raised.getTicketNumber());
    }

    @Override
    public List<EventOutcome> drainPending(int limit) {
        List<NetworkEvent> batch = pending(limit);
        List<EventOutcome> outcomes = new ArrayList<EventOutcome>(batch.size());
        for (NetworkEvent event : batch) {
            outcomes.add(process(event));
        }
        if (!outcomes.isEmpty()) {
            AppLogger.info(NetworkEventServiceImpl.class, "Drained " + outcomes.size()
                    + " alarm(s); " + ticketsRaisedIn(outcomes) + " raised a ticket");
        }
        return outcomes;
    }

    /* ---------- Raising the ticket ---------- */

    /**
     * Opens a ticket for an alarm, as the system rather than as a person.
     *
     * <p>The same sequence {@code TicketServiceImpl.store} follows, because
     * a ticket raised by a machine is still a ticket: it gets a number, its
     * deadlines from the configured clock, an opening line in its history
     * and an announcement. What differs is the actor, which is
     * {@link TicketEvent#SYSTEM_ACTOR}, and the {@code auto_created} flag,
     * which is what lets the incident report separate the two.</p>
     */
    private TroubleTicket raiseTicket(NetworkEvent event, TelecomService service,
                                      IncidentCategory category) {
        Severity severity = event.getSeverity();
        Priority priority = severity.toDefaultPriority();
        String description = Validators.shorten("Raised automatically from network alarm "
                        + event.getEventReference() + ": " + event.getEventType().getDisplayName()
                        + " on " + event.getNetworkNode(),
                TicketValidator.DESCRIPTION_MAX);

        for (int attempt = 1; attempt <= 3; attempt++) {
            String ticketNumber = numbers.next();
            try {
                TroubleTicket ticket = new TroubleTicket(ticketNumber, service.getCustomerId(),
                        service.getId(), category, description, priority, severity,
                        TicketEvent.SYSTEM_ACTOR);
                ticket.setStatus(TicketStatus.OPEN);
                ticket.setSlaStatus(SLAStatus.WITHIN_SLA);
                ticket.setAutoCreated(true);
                sla.stampDeadlines(ticket);

                TroubleTicket stored = tickets.insert(ticket);

                history.insert(new TicketStatusHistory(stored.getId(), null, TicketStatus.OPEN,
                        TicketEvent.SYSTEM_ACTOR, Validators.shorten(
                        "Opened by the event processor from alarm " + event.getEventReference(),
                        TicketValidator.REMARKS_MAX)));

                publisher.publish(TicketEvent.autoRaised(stored,
                        event.getEventType().getDisplayName() + " on " + event.getNetworkNode()));

                AppLogger.info(NetworkEventServiceImpl.class, "Ticket " + ticketNumber
                        + " opened automatically from alarm " + event.getEventReference());
                return stored;
            } catch (DuplicateResourceException collision) {
                // Another thread or another process took the number between
                // the counter handing it out and the insert. Re-read and
                // ask again; the unique constraint is what makes this safe.
                AppLogger.warn(NetworkEventServiceImpl.class, "Ticket number " + ticketNumber
                        + " was taken while alarm " + event.getEventReference()
                        + " was being processed; retrying (attempt " + attempt + ")");
                numbers.resync();
            }
        }
        throw new IllegalStateException("Could not obtain an unused ticket number for alarm "
                + event.getEventReference());
    }

    /* ---------- Choosing what was affected ---------- */

    /**
     * The service an alarm is attributed to, empty when nothing in the
     * region can carry a ticket.
     */
    private Optional<TelecomService> affectedService(NetworkEvent event) {
        if (event.getRegion() == null) {
            return Optional.empty();
        }
        List<Customer> affected = customers.findByRegion(event.getRegion()).stream()
                .filter(customer -> customer.getStatus() != null
                        && customer.getStatus().canRaiseTicket())
                .sorted(Comparator.comparingInt(NetworkEventServiceImpl::tierOf).reversed()
                        .thenComparing(Customer::getId))
                .collect(Collectors.toList());

        for (Customer customer : affected) {
            Optional<TelecomService> oldest =
                    services.findTicketableByCustomerId(customer.getId()).stream()
                            .min(Comparator.comparing(TelecomService::getId));
            if (oldest.isPresent()) {
                return oldest;
            }
        }
        return Optional.empty();
    }

    /**
     * An open automatic ticket already covering this fault, if there is one.
     */
    private Optional<TroubleTicket> openAutomaticTicket(Long serviceId,
                                                        IncidentCategory category) {
        return tickets.findOpen().stream()
                .filter(TroubleTicket::isAutoCreated)
                .filter(ticket -> serviceId.equals(ticket.getServiceId()))
                .filter(ticket -> ticket.getCategory() == category)
                .min(Comparator.comparing(TroubleTicket::getId));
    }

    /* ---------- Looking at the stream ---------- */

    @Override
    public Map<EventStatus, Long> countByStatus() {
        Map<EventStatus, Long> counts = new EnumMap<EventStatus, Long>(EventStatus.class);
        for (EventStatus status : EventStatus.values()) {
            counts.put(status, Long.valueOf(events.findByStatus(status).size()));
        }
        return counts;
    }

    @Override
    public List<NetworkEvent> simulate(int count) {
        return simulator.next(count);
    }

    /* ---------- Wording ---------- */

    private static String notWorthATicket(NetworkEvent event) {
        if (event.getEventType() != null && !event.getEventType().isTicketWorthy()) {
            return event.getEventType().getDisplayName()
                    + " is recorded but does not warrant a ticket";
        }
        return "Severity "
                + (event.getSeverity() == null ? "unknown" : event.getSeverity().getDisplayName())
                + " is informational only";
    }

    private static String nothingAffected(NetworkEvent event) {
        return event.getRegion() == null
                ? "The alarm names no region, so no service can be attributed to it"
                : "No active service in " + event.getRegion().getDisplayName()
                        + " could carry a ticket";
    }

    /* ---------- Shared ---------- */

    private static int tierOf(Customer customer) {
        CustomerType type = customer.getCustomerType();
        return type == null ? 0 : type.getTierWeight();
    }

    private static void requireAlarm(NetworkEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("An alarm is required");
        }
        if (Validators.isBlank(event.getEventReference())
                || Validators.isBlank(event.getNetworkNode())
                || event.getEventType() == null || event.getSeverity() == null) {
            throw new IllegalArgumentException("An alarm needs a reference, a node, "
                    + "a type and a severity before it can be recorded");
        }
    }

    private static void requireStored(NetworkEvent event) {
        requireAlarm(event);
        if (event.getId() == null) {
            throw new IllegalArgumentException("An alarm must be recorded before it is processed, "
                    + "because processing claims the row");
        }
    }

    private static long ticketsRaisedIn(List<EventOutcome> outcomes) {
        return outcomes.stream().filter(EventOutcome::isTicketRaised).count();
    }

    private static String messageOf(RuntimeException failure) {
        return failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    private static int bounded(int requested) {
        if (requested <= 0) {
            return DEFAULT_BATCH;
        }
        return Math.min(requested, MAX_BATCH);
    }
}
