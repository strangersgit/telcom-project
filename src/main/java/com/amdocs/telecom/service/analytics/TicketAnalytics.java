package com.amdocs.telecom.service.analytics;

import com.amdocs.telecom.dao.DAOFactory;
import com.amdocs.telecom.model.Customer;
import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.NetworkEngineer;
import com.amdocs.telecom.model.TroubleTicket;
import com.amdocs.telecom.model.enums.EngineerAvailability;
import com.amdocs.telecom.model.enums.IncidentCategory;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.model.enums.SLAStatus;
import com.amdocs.telecom.model.enums.Specialization;
import com.amdocs.telecom.model.enums.TicketStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The nine analyses section 16 asks the Stream API for.
 *
 * <h3>Why these run in Java when the database can already do them</h3>
 *
 * <p>Several of these numbers also exist as views, and the views are not
 * redundant: a view aggregates the whole table in the database, which is
 * the right way to answer "how is the operation doing" without dragging
 * every row across the wire. What a view cannot do is answer the same
 * question about an arbitrary subset. These analyses take a list of
 * tickets, so the same nine questions can be asked of one region, one
 * month, one customer, or the results of a search, without a new view for
 * each combination.</p>
 *
 * <p>Having both also buys a check that neither alone could give. The
 * definitions here match {@code vw_sla_compliance},
 * {@code vw_engineer_workload}, {@code vw_category_incidents} and
 * {@code vw_customer_repeat_incidents} exactly, down to counting a ticket
 * as met or breached only once it has a resolution date, so the two can be
 * run against the same data and compared. When the SQL and the Java agree,
 * both are probably right; when they disagree, one of them has a bug that
 * neither would have revealed on its own.</p>
 *
 * <h3>Immutable and safe to share</h3>
 *
 * <p>The ticket list is copied and wrapped on the way in, and nothing here
 * writes. That matters because {@code ReportGenerator} runs reports on a
 * pool: several threads can hold the same analytics and ask different
 * questions at once without any locking at all.</p>
 */
public final class TicketAnalytics {

    private final List<TroubleTicket> tickets;
    private final Map<Long, Customer> customersById;
    private final Map<Long, NetworkEngineer> engineersById;

    private TicketAnalytics(List<TroubleTicket> tickets, List<Customer> customers,
                            List<NetworkEngineer> engineers) {
        this.tickets = Collections.unmodifiableList(new ArrayList<TroubleTicket>(tickets));
        this.customersById = index(customers, Customer::getId);
        this.engineersById = index(engineers, NetworkEngineer::getId);
    }

    /**
     * Analyses a particular set of tickets.
     *
     * <p>The customers and engineers are needed because a ticket carries
     * ids, not names or regions, and four of the nine analyses are about
     * something a ticket does not itself know.</p>
     */
    public static TicketAnalytics over(List<TroubleTicket> tickets, List<Customer> customers,
                                       List<NetworkEngineer> engineers) {
        return new TicketAnalytics(
                tickets == null ? Collections.<TroubleTicket>emptyList() : tickets,
                customers == null ? Collections.<Customer>emptyList() : customers,
                engineers == null ? Collections.<NetworkEngineer>emptyList() : engineers);
    }

    /**
     * Analyses everything, which is what the operation wide reports want.
     *
     * <p>Three reads, once, and then every question is answered in memory.
     * The alternative, a query per question, would be nine round trips for
     * a report pack that is meant to be one.</p>
     */
    public static TicketAnalytics loadAll(DAOFactory factory) {
        return over(factory.getTroubleTicketDAO().findAll(),
                factory.getCustomerDAO().findAll(),
                factory.getNetworkEngineerDAO().findAll());
    }

    /** The tickets being analysed, unmodifiable. */
    public List<TroubleTicket> getTickets() {
        return tickets;
    }

    public int size() {
        return tickets.size();
    }

    public boolean isEmpty() {
        return tickets.isEmpty();
    }

    /* ---------- 1. Tickets by status ---------- */

    /**
     * How many tickets sit in each status, every status listed.
     *
     * <p>Statuses nobody is in are shown as zero rather than left out. A
     * report that silently omits CANCELLED reads as though cancelling were
     * impossible.</p>
     */
    public List<Tally<TicketStatus>> byStatus() {
        return tally(TicketStatus.values(), TroubleTicket::getStatus,
                TicketStatus::getDisplayName);
    }

    /* ---------- 2. Tickets by priority ---------- */

    public List<Tally<Priority>> byPriority() {
        return tally(Priority.values(), TroubleTicket::getPriority, Priority::getDisplayName);
    }

    /* ---------- 3 and 8. Engineer workload and performance ---------- */

    /**
     * One row per engineer: what they are carrying and what they have
     * finished.
     *
     * <p>Grouped once and sorted by the caller's question. The tickets are
     * bucketed by engineer in a single pass, then each bucket is reduced,
     * rather than filtering the whole list once per engineer, which would
     * be quadratic in a system with a few hundred engineers.</p>
     */
    public List<EngineerScorecard> engineerScorecards() {
        Map<Long, List<TroubleTicket>> byEngineer = tickets.stream()
                .filter(TroubleTicket::isAssigned)
                .collect(Collectors.groupingBy(TroubleTicket::getAssignedEngineerId));

        List<EngineerScorecard> cards = new ArrayList<EngineerScorecard>(engineersById.size());
        for (NetworkEngineer engineer : engineersById.values()) {
            List<TroubleTicket> theirs = byEngineer.get(engineer.getId());
            cards.add(scorecardFor(engineer,
                    theirs == null ? Collections.<TroubleTicket>emptyList() : theirs));
        }
        cards.sort(EngineerScorecard.byWorkload());
        return cards;
    }

    /** Busiest engineers first. */
    public List<EngineerScorecard> engineerWorkload() {
        return engineerScorecards();
    }

    /** Most productive engineers first. */
    public List<EngineerScorecard> engineerPerformance() {
        List<EngineerScorecard> cards = engineerScorecards();
        cards.sort(EngineerScorecard.byPerformance());
        return cards;
    }

    private EngineerScorecard scorecardFor(NetworkEngineer engineer,
                                           List<TroubleTicket> theirs) {
        long resolved = theirs.stream().filter(TicketAnalytics::isCompleted).count();
        long open = theirs.stream().filter(TicketAnalytics::isStillOpen).count();
        long breaches = theirs.stream().filter(TicketAnalytics::resolvedLate).count();
        return new EngineerScorecard(engineer.getId(), engineer.getEmployeeCode(),
                engineer.getEngineerName(), engineer.getSpecialization(), engineer.getRegion(),
                engineer.getExperienceYears(), engineer.getAvailability(),
                theirs.size(), resolved, open, breaches, averageHours(theirs));
    }

    /* ---------- 4. SLA breach analysis ---------- */

    /**
     * Met against breached, per priority band.
     *
     * <p>Every band appears, including ones with no tickets, because the
     * absence of CRITICAL incidents this week is itself worth seeing.</p>
     */
    public List<SlaOutcome> slaBreachAnalysis() {
        Map<Priority, List<TroubleTicket>> byBand = tickets.stream()
                .filter(ticket -> ticket.getPriority() != null)
                .collect(Collectors.groupingBy(TroubleTicket::getPriority));

        List<SlaOutcome> outcomes = new ArrayList<SlaOutcome>(Priority.values().length);
        for (Priority priority : Priority.values()) {
            List<TroubleTicket> band = byBand.get(priority);
            outcomes.add(outcomeFor(priority,
                    band == null ? Collections.<TroubleTicket>emptyList() : band));
        }
        return outcomes;
    }

    private SlaOutcome outcomeFor(Priority priority, List<TroubleTicket> band) {
        long completed = band.stream().filter(TicketAnalytics::isCompleted).count();
        long met = band.stream().filter(TicketAnalytics::resolvedOnTime).count();
        long breached = band.stream().filter(TicketAnalytics::resolvedLate).count();
        long decided = met + breached;
        Double compliance = decided == 0L ? null
                : Math.round(10000.0d * met / decided) / 100.0d;
        return new SlaOutcome(priority, band.size(), completed, met, breached, compliance,
                averageHours(band));
    }

    /**
     * How many open tickets stand in each SLA state right now.
     *
     * <p>Uses the stored status rather than recomputing, because the stored
     * one is what the SLA monitor maintains and what the console shows; a
     * report that quietly disagreed with the dashboard would be worse than
     * one that was a minute stale.</p>
     */
    public List<Tally<SLAStatus>> openBySlaStatus() {
        List<TroubleTicket> open = tickets.stream()
                .filter(TicketAnalytics::isStillOpen)
                .collect(Collectors.toList());
        Map<SLAStatus, Long> counts = open.stream()
                .map(TroubleTicket::getSlaStatus)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        List<Tally<SLAStatus>> tallies = new ArrayList<Tally<SLAStatus>>();
        for (SLAStatus status : SLAStatus.values()) {
            Long count = counts.get(status);
            tallies.add(Tally.of(status, status.getDisplayName(),
                    count == null ? 0L : count, open.size()));
        }
        return tallies;
    }

    /* ---------- 5. Average resolution time ---------- */

    /**
     * The mean end to end resolution time in hours, over tickets that have
     * one.
     *
     * <p>Empty when nothing has been resolved. An average of no numbers is
     * not zero hours, and reporting it as zero would say the team is
     * infinitely fast.</p>
     */
    public OptionalDouble averageResolutionHours() {
        return tickets.stream()
                .map(TroubleTicket::getResolutionHours)
                .filter(Optional::isPresent)
                .mapToDouble(Optional::get)
                .average();
    }

    /**
     * The same, per priority band, so a slow average can be attributed.
     */
    public Map<Priority, Double> averageResolutionHoursByPriority() {
        return averageResolutionHoursBy(TroubleTicket::getPriority);
    }

    /**
     * How long resolution takes for each kind of fault.
     */
    public Map<IncidentCategory, Double> averageResolutionHoursByCategory() {
        return averageResolutionHoursBy(TroubleTicket::getCategory);
    }

    /**
     * Mean resolution hours for each group, slowest group first.
     *
     * <p>Generic because four reports want this same shape over four
     * different groupings, and writing it once means they cannot disagree
     * about how an average is taken. Groups where nothing has been
     * resolved are left out: an average of nothing is not a number, and a
     * report row reading 0.00 hours would be read as instant.</p>
     *
     * <p>Slowest first because that is the order somebody looking to
     * improve things reads it in.</p>
     */
    public <T> Map<T, Double> averageResolutionHoursBy(
            Function<TroubleTicket, T> classifier) {
        Map<T, List<TroubleTicket>> grouped = tickets.stream()
                .filter(ticket -> classifier.apply(ticket) != null)
                .collect(Collectors.groupingBy(classifier));

        Map<T, Double> averages = new LinkedHashMap<T, Double>();
        grouped.entrySet().stream()
                .filter(entry -> averageHours(entry.getValue()) != null)
                .sorted(Comparator.comparingDouble(
                        (Map.Entry<T, List<TroubleTicket>> entry) ->
                                averageHours(entry.getValue())).reversed())
                .forEach(entry -> averages.put(entry.getKey(), averageHours(entry.getValue())));
        return averages;
    }

    /**
     * Counts the tickets that pass a test, grouped by anything.
     *
     * <p>The building block the reports compose: critical tickets per
     * region, automatically raised tickets per category, open tickets per
     * engineer. Each of those would otherwise be another method here that
     * differs from its neighbours by one predicate.</p>
     *
     * @param filter which tickets to count, or null for all of them
     */
    public <T> Map<T, Long> countBy(Function<TroubleTicket, T> classifier,
                                    Predicate<TroubleTicket> filter) {
        return tickets.stream()
                .filter(filter == null ? ticket -> true : filter)
                .filter(ticket -> classifier.apply(ticket) != null)
                .collect(Collectors.groupingBy(classifier, Collectors.counting()));
    }

    /* ---------- 6. Tickets by region ---------- */

    /**
     * Where the trouble is, by the region of the customer who reported it.
     *
     * <p>A ticket has no region of its own; the customer does. Tickets
     * whose customer cannot be found are left out rather than bucketed into
     * a fictional region, and {@link #ticketsWithoutRegion()} says how many
     * that was so the reader can tell a quiet region from a gap in the
     * data.</p>
     */
    public List<Tally<Region>> byRegion() {
        return tally(Region.values(), this::regionOf, Region::getDisplayName);
    }

    /**
     * The region a ticket belongs to, by way of the customer who reported
     * it, or null when the customer is not in this snapshot.
     */
    public Region regionOf(TroubleTicket ticket) {
        Customer customer = ticket == null ? null : customersById.get(ticket.getCustomerId());
        return customer == null ? null : customer.getRegion();
    }

    /** The customer who reported a ticket, if they are in this snapshot. */
    public Optional<Customer> findCustomer(TroubleTicket ticket) {
        return ticket == null ? Optional.<Customer>empty()
                : Optional.ofNullable(customersById.get(ticket.getCustomerId()));
    }

    /** The engineer holding a ticket, if there is one. */
    public Optional<NetworkEngineer> findEngineer(TroubleTicket ticket) {
        return ticket == null ? Optional.<NetworkEngineer>empty()
                : Optional.ofNullable(engineersById.get(ticket.getAssignedEngineerId()));
    }

    /**
     * Tickets that could not be placed in a region, which should be none.
     */
    public long ticketsWithoutRegion() {
        return tickets.stream().filter(ticket -> regionOf(ticket) == null).count();
    }

    /* ---------- 7. Top incident categories ---------- */

    /**
     * Every category, busiest first.
     */
    public List<Tally<IncidentCategory>> byCategory() {
        List<Tally<IncidentCategory>> tallies = tally(IncidentCategory.values(),
                TroubleTicket::getCategory, IncidentCategory::getDisplayName);
        tallies.sort(Tally.<IncidentCategory>byCountDescending());
        return tallies;
    }

    /**
     * The worst few, with empty categories dropped.
     *
     * @param limit how many to return; anything below one gives them all
     */
    public List<Tally<IncidentCategory>> topCategories(int limit) {
        List<Tally<IncidentCategory>> ranked = byCategory().stream()
                .filter(tally -> !tally.isEmpty())
                .collect(Collectors.toList());
        if (limit < 1 || limit >= ranked.size()) {
            return ranked;
        }
        return new ArrayList<Tally<IncidentCategory>>(ranked.subList(0, limit));
    }

    /* ---------- 9. Customers with repeated incidents ---------- */

    /**
     * Customers who have raised more than one ticket, most first.
     *
     * @param threshold the fewest incidents that counts as repeating; below
     *                  two it is treated as two, because one incident is
     *                  not a repeat
     */
    public List<RepeatCustomer> repeatIncidents(int threshold) {
        final long floor = Math.max(threshold, 2);
        Map<Long, List<TroubleTicket>> byCustomer = tickets.stream()
                .filter(ticket -> ticket.getCustomerId() != null)
                .collect(Collectors.groupingBy(TroubleTicket::getCustomerId));

        List<RepeatCustomer> repeats = new ArrayList<RepeatCustomer>();
        for (Map.Entry<Long, List<TroubleTicket>> entry : byCustomer.entrySet()) {
            if (entry.getValue().size() < floor) {
                continue;
            }
            Customer customer = customersById.get(entry.getKey());
            if (customer == null) {
                continue;
            }
            repeats.add(repeatFor(customer, entry.getValue()));
        }
        repeats.sort(RepeatCustomer.mostTroubledFirst());
        return repeats;
    }

    private RepeatCustomer repeatFor(Customer customer, List<TroubleTicket> theirs) {
        long critical = theirs.stream()
                .filter(ticket -> ticket.getPriority() == Priority.CRITICAL)
                .count();
        return new RepeatCustomer(customer.getId(), customer.getCustomerNumber(),
                customer.getCustomerName(), customer.getCustomerType(), customer.getCity(),
                customer.getRegion(), theirs.size(), critical, latestOf(theirs));
    }

    /* ---------- The worked example from section 16 ---------- */

    /**
     * "Find the three engineers with the lowest active workload who have
     * the required specialization and are currently available."
     *
     * <p>The document's own example, written the way it reads: filter to
     * the specialists, filter to the available, sort by what they are
     * carrying, take the first few. Experience breaks a tie, because
     * between two engineers with one ticket each the more experienced is
     * the better bet.</p>
     *
     * <p>This is the in-memory twin of {@code sp_recommend_engineers}, and
     * it answers the same question over whatever roster it is given rather
     * than over the whole table.</p>
     */
    public List<NetworkEngineer> lightestLoaded(Specialization specialization, int limit) {
        return engineersById.values().stream()
                .filter(engineer -> specialization == null
                        || engineer.getSpecialization() == specialization)
                .filter(engineer -> engineer.getAvailability() == EngineerAvailability.AVAILABLE)
                .sorted(Comparator.comparingInt(NetworkEngineer::getActiveTicketCount)
                        .thenComparing(Comparator.comparingInt(
                                NetworkEngineer::getExperienceYears).reversed())
                        .thenComparing(NetworkEngineer::getEmployeeCode))
                .limit(limit < 1 ? 3L : limit)
                .collect(Collectors.toList());
    }

    /* ---------- Cross cutting counts a dashboard wants ---------- */

    public long countOpen() {
        return tickets.stream().filter(TicketAnalytics::isStillOpen).count();
    }

    public long countCriticalOpen() {
        return tickets.stream()
                .filter(TicketAnalytics::isStillOpen)
                .filter(ticket -> ticket.getPriority() == Priority.CRITICAL)
                .count();
    }

    public long countResolvedOn(LocalDate day) {
        if (day == null) {
            return 0L;
        }
        return tickets.stream()
                .map(TroubleTicket::getResolutionDate)
                .filter(Objects::nonNull)
                .filter(resolved -> day.equals(resolved.toLocalDate()))
                .count();
    }

    /**
     * Tickets raised per day over a window, every day present even when
     * nothing happened.
     *
     * <p>A gap in a volume report reads as missing data. A zero reads as a
     * quiet day, which is what it was.</p>
     */
    public List<Tally<LocalDate>> volumeByDay(LocalDate from, LocalDate to) {
        return perDay(from, to, TroubleTicket::getCreatedDate);
    }

    /**
     * Tickets resolved per day over the same window.
     *
     * <p>Raised and resolved side by side is what makes a volume report
     * worth reading: either number alone says how busy the day was, and
     * only the pair says whether the backlog grew.</p>
     */
    public List<Tally<LocalDate>> resolvedByDay(LocalDate from, LocalDate to) {
        return perDay(from, to, TroubleTicket::getResolutionDate);
    }

    private List<Tally<LocalDate>> perDay(LocalDate from, LocalDate to,
                                          Function<TroubleTicket, LocalDateTime> dateOf) {
        if (from == null || to == null || to.isBefore(from)) {
            return Collections.emptyList();
        }
        Map<LocalDate, Long> counted = tickets.stream()
                .map(dateOf)
                .filter(Objects::nonNull)
                .map(LocalDateTime::toLocalDate)
                .filter(day -> !day.isBefore(from) && !day.isAfter(to))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        long total = counted.values().stream().mapToLong(Long::longValue).sum();
        List<Tally<LocalDate>> days = new ArrayList<Tally<LocalDate>>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            Long count = counted.get(day);
            days.add(Tally.of(day, Displayable.formatDate(day),
                    count == null ? 0L : count, total));
        }
        return days;
    }

    /**
     * The critical incidents, worst first, for the report of that name.
     */
    public List<TroubleTicket> criticalIncidents(boolean openOnly) {
        Predicate<TroubleTicket> wanted = ticket ->
                ticket.getPriority() == Priority.CRITICAL
                        && (!openOnly || isStillOpen(ticket));
        return tickets.stream()
                .filter(wanted)
                .sorted(TroubleTicket.byUrgency())
                .collect(Collectors.toList());
    }

    /* ---------- Shared plumbing ---------- */

    /**
     * Counts tickets into a fixed set of buckets, keeping the empty ones.
     */
    private <T> List<Tally<T>> tally(T[] universe, Function<TroubleTicket, T> classifier,
                                     Function<T, String> labeller) {
        Map<T, Long> counts = tickets.stream()
                .map(classifier)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        List<Tally<T>> tallies = new ArrayList<Tally<T>>(universe.length);
        for (T value : universe) {
            Long count = counts.get(value);
            tallies.add(Tally.of(value, labeller.apply(value),
                    count == null ? 0L : count, tickets.size()));
        }
        return tallies;
    }

    /**
     * Mean resolution hours over the tickets that have a resolution,
     * rounded to two places.
     *
     * <p>Rounded the same way {@code fn_resolution_hours} is, and averaged
     * over the already rounded per ticket figures, because that is what
     * {@code AVG(fn_resolution_hours(...))} does in the views. Averaging
     * the unrounded values instead would differ in the last place and turn
     * the cross check into a puzzle.</p>
     */
    private static Double averageHours(List<TroubleTicket> group) {
        OptionalDouble average = group.stream()
                .map(TroubleTicket::getResolutionHours)
                .filter(Optional::isPresent)
                .mapToDouble(Optional::get)
                .average();
        return average.isPresent() ? Math.round(average.getAsDouble() * 100.0d) / 100.0d : null;
    }

    private static LocalDateTime latestOf(List<TroubleTicket> group) {
        return group.stream()
                .map(TroubleTicket::getCreatedDate)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    /*
     * The four predicates below are public because the reports compose
     * them with countBy, and because they are the definitions the views
     * use. Leaving them private would have each caller write its own
     * slightly different idea of what "open" means, which is exactly how
     * two reports of the same data end up disagreeing.
     */

    /** Resolved or closed, matching the views' definition of completed. */
    public static boolean isCompleted(TroubleTicket ticket) {
        return ticket.getStatus() == TicketStatus.RESOLVED
                || ticket.getStatus() == TicketStatus.CLOSED;
    }

    /** Neither finished nor cancelled, matching the views' "currently open". */
    public static boolean isStillOpen(TroubleTicket ticket) {
        TicketStatus status = ticket.getStatus();
        return status != null && status != TicketStatus.RESOLVED
                && status != TicketStatus.CLOSED && status != TicketStatus.CANCELLED;
    }

    /** Resolved within the deadline. */
    public static boolean resolvedOnTime(TroubleTicket ticket) {
        return ticket.getResolutionDate() != null && ticket.getSlaDeadline() != null
                && !ticket.getResolutionDate().isAfter(ticket.getSlaDeadline());
    }

    /** Resolved, but after the deadline. */
    public static boolean resolvedLate(TroubleTicket ticket) {
        return ticket.getResolutionDate() != null && ticket.getSlaDeadline() != null
                && ticket.getResolutionDate().isAfter(ticket.getSlaDeadline());
    }

    public static boolean isCritical(TroubleTicket ticket) {
        return ticket.getPriority() == Priority.CRITICAL;
    }

    private static <T> Map<Long, T> index(List<T> values, Function<T, Long> key) {
        Map<Long, T> byId = new LinkedHashMap<Long, T>();
        for (T value : values) {
            Long id = key.apply(value);
            if (id != null) {
                byId.put(id, value);
            }
        }
        return Collections.unmodifiableMap(byId);
    }
}
