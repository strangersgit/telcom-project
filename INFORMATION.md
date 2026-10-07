# Information File — Where Each Required Concept Is Implemented

This file lists every concept the case study asks for (see `TRACEABILITY.md`)
and shows the exact file, line numbers and the code where it is used.

All paths are relative to the project root. Java sources live under
`src/main/java/com/amdocs/telecom/`.

---

## Quick Index

| # | Concept | Main file(s) |
|---|---|---|
| 1 | Stream API | `service/analytics/TicketAnalytics.java`, `service/assignment/EngineerRecommender.java` |
| 2 | Collectors (`groupingBy`, `counting`, `toMap`, `joining`) | `service/analytics/TicketAnalytics.java`, `util/ReportVerification.java` |
| 3 | Lambda expressions | `EngineerRecommender.java`, `controller/MainMenu.java`, `TicketAnalytics.java` |
| 4 | Functional interfaces | `dao/RowMapper.java`, `service/sla/SlaClock.java`, `service/event/TicketEventListener.java` |
| 5 | `Optional` | `EngineerRecommender.java`, `dao/impl/CustomerDAOImpl.java`, `dao/impl/TroubleTicketDAOImpl.java` |
| 6 | Method references | `controller/ServiceDeskDashboard.java`, `dao/impl/CustomerDAOImpl.java` |
| 7 | Default and static interface methods | `model/Displayable.java`, `model/enums/DescribableEnum.java`, `service/sla/SlaClock.java` |
| 8 | Comparator chaining | `model/NetworkEngineer.java`, `dto/OpenTicketDTO.java`, `service/assignment/EngineerMatch.java` |
| 9 | Date/Time API | `service/sla/BusinessHoursSlaClock.java`, `service/sla/ContinuousSlaClock.java`, `security/UserSession.java` |
| 10 | The nine Java 8 analyses | `service/analytics/TicketAnalytics.java` |
| 11 | `ExecutorService` | `scheduler/NetworkEventProcessor.java`, `report/ReportGenerator.java` |
| 12 | `ScheduledExecutorService` | `scheduler/SlaMonitor.java`, `scheduler/NotificationProcessor.java`, `scheduler/NetworkEventFeeder.java` |
| 13 | `BlockingQueue` (producer / consumer) | `scheduler/NetworkEventProcessor.java`, `scheduler/NetworkEventFeeder.java` |
| 14 | `Runnable` | `scheduler/NetworkEventProcessor.java`, `scheduler/NamedThreads.java`, `scheduler/BackgroundServices.java` |
| 15 | `Callable` and `Future` | `report/ReportGenerator.java` |
| 16 | Synchronization (`synchronized`, `wait/notifyAll`, `volatile`, atomics, concurrent maps) | `scheduler/*`, `report/ReportGenerator.java`, `service/impl/TicketNumberGenerator.java`, `security/SessionContext.java` |
| 17 | Named threads (`ThreadFactory`) | `scheduler/NamedThreads.java` |
| 18 | Clean shutdown and interrupt handling | `scheduler/NetworkEventProcessor.java`, `scheduler/SlaMonitor.java`, `scheduler/BackgroundServices.java` |
| 19 | `ThreadLocal` | `dao/ConnectionScope.java`, `dao/TransactionTemplate.java` |
| 20 | `PriorityQueue` | `service/impl/EscalationServiceImpl.java`, `service/escalation/EscalationCandidate.java` |
| 21 | Database-level concurrency guard | `service/impl/NetworkEventServiceImpl.java`, `dao/impl/NetworkEventDAOImpl.java` |
| 22 | Custom exception hierarchy | `exception/*` |
| 23 | try / catch / finally | `dao/TransactionTemplate.java`, `service/impl/AuthenticationServiceImpl.java`, `scheduler/NetworkEventProcessor.java` |
| 24 | Multi-catch | `util/DBConnection.java`, `util/DatabaseBootstrap.java`, `security/PasswordHasher.java` |
| 25 | try-with-resources | `util/DBConnection.java`, `dao/impl/JdbcOperations.java`, `report/ReportExporter.java` |
| 26 | Exception wrapping / translation | `util/DBConnection.java`, `dao/impl/JdbcOperations.java`, `util/ConfigLoader.java` |
| 27 | Throwing custom exceptions for rules | `service/impl/TicketServiceImpl.java`, `validation/TicketLifecycle.java`, `security/AccessControl.java` |
| 28 | Log-and-skip failure handling in workers | `scheduler/NetworkEventProcessor.java`, `scheduler/NotificationProcessor.java`, `service/impl/EngineerAssignmentServiceImpl.java` |
| 29 | Top-level exception handling | `main/TSATMSApplication.java` |
| 30 | JDBC Connection | `util/DBConnection.java`, `dao/ConnectionScope.java` |
| 31 | `PreparedStatement` | `dao/impl/JdbcOperations.java`, `dao/impl/JdbcSupport.java` |
| 32 | `ResultSet` + `RowMapper` | `dao/RowMapper.java`, `dao/impl/JdbcOperations.java`, `dao/impl/TroubleTicketDAOImpl.java` |
| 33 | `CallableStatement` (stored procedures) | `dao/impl/ReportDAOImpl.java` |
| 34 | Transactions (commit / rollback) | `dao/TransactionTemplate.java` |
| 35 | Savepoint | `dao/TransactionContext.java`, `dao/TransactionTemplate.java`, `service/impl/EngineerAssignmentServiceImpl.java` |
| 36 | Batch processing | `dao/impl/JdbcOperations.java`, `dao/impl/NotificationDAOImpl.java` |
| 37 | Section 19 assignment transaction | `service/impl/EngineerAssignmentServiceImpl.java` |
| 38 | Encapsulation | `model/TroubleTicket.java` |
| 39 | Inheritance | `model/TroubleTicket.java`, `model/Customer.java` |
| 40 | Abstract classes | `model/AbstractParty.java`, `dao/impl/AbstractJdbcDAO.java`, `controller/Dashboard.java` |
| 41 | Interfaces | `model/Identifiable.java`, `model/Displayable.java`, `model/Auditable.java`, `model/enums/DescribableEnum.java` |
| 42 | Polymorphism | `service/impl/SlaServiceImpl.java`, `report/ReportExporter.java` |
| 43 | Generics | `dao/GenericDAO.java`, `dao/RowMapper.java`, `service/analytics/Tally.java` |
| 44 | Enums with behaviour | `model/enums/Priority.java`, `TicketStatus.java`, `EscalationLevel.java`, `Role.java` |
| 45 | `equals` / `hashCode` / `toString` | `model/BaseEntity.java` |
| 46 | Collections (`List`, `EnumMap`, `EnumSet`, `Queue`) | `dao/impl/AbstractJdbcDAO.java`, `validation/TicketLifecycle.java`, `security/Permission.java` |
| 47 | Singleton | `util/ConfigLoader.java`, `util/DBConnection.java`, `dao/DAOFactory.java` |
| 48 | Factory | `dao/DAOFactory.java`, `report/ReportFormat.java` |
| 49 | Strategy | `service/sla/SlaClockRegistry.java`, `report/ReportWriter.java` |
| 50 | Observer | `service/event/TicketEventPublisher.java`, `service/event/AuditTrailListener.java`, `service/impl/NotificationServiceImpl.java` |
| 51 | Template Method | `controller/Dashboard.java`, `scheduler/BackgroundServices.java` |
| 52 | DAO pattern | `dao/TroubleTicketDAO.java`, `dao/impl/TroubleTicketDAOImpl.java` |
| 53 | File handling (config, SQL scripts, reports, logs) | `util/ConfigLoader.java`, `util/SqlScriptRunner.java`, `report/ReportExporter.java`, `report/CsvReportWriter.java`, `util/AppLogger.java` |
| 54 | Security (CAPTCHA, PBKDF2, OTP, lockout, RBAC) | `security/*`, `service/impl/AuthenticationServiceImpl.java`, `model/UserAccount.java` |
| 55 | Validation and state machine | `validation/Validators.java`, `validation/TicketValidator.java`, `validation/TicketLifecycle.java` |

---

# PART A — JAVA 8 FEATURES (Case study section 16)

## 1. Stream API

### 1a. Filter → sort → limit → collect

**File:** `src/main/java/com/amdocs/telecom/service/assignment/EngineerRecommender.java`
**Lines:** 148–158

```java
    public List<NetworkEngineer> lowestWorkload(List<NetworkEngineer> roster,
                                                Specialization required, int count) {
        if (roster == null || required == null) {
            return Collections.emptyList();
        }
        return roster.stream()
                .filter(CAN_TAKE_WORK)
                .filter(engineer -> engineer.hasSpecialization(required))
                .sorted(NetworkEngineer.byWorkloadThenExperience())
                .limit(boundedLimit(count))
                .collect(Collectors.toList());
    }
```

Keeps only engineers who can take work and have the right specialization, sorts them by workload, takes the top N and collects them into a list.

### 1b. Several streams joined with `Stream.concat`

**File:** `src/main/java/com/amdocs/telecom/service/assignment/EngineerRecommender.java`
**Lines:** 103–115

```java
        Stream<EngineerMatch> exact = matches(roster, MatchTier.EXACT,
                engineer -> engineer.hasSpecialization(required)
                        && engineer.servesRegion(region));
        Stream<EngineerMatch> remote = matches(roster, MatchTier.OUT_OF_REGION,
                engineer -> engineer.hasSpecialization(required)
                        && !engineer.servesRegion(region));
        Stream<EngineerMatch> local = matches(roster, MatchTier.REGION_ONLY,
                engineer -> !engineer.hasSpecialization(required)
                        && engineer.servesRegion(region));

        return Stream.concat(exact, Stream.concat(remote, local))
                .sorted(EngineerMatch.preferredOrder())
                .collect(Collectors.toList());
```

Builds one stream per match tier, joins them, sorts by preference and collects the ranked list.

### 1c. Section 16 worked example — three least-loaded available engineers

**File:** `src/main/java/com/amdocs/telecom/service/analytics/TicketAnalytics.java`
**Lines:** 446–456

```java
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
```

---

## 2. Collectors

> `Collectors.partitioningBy` and `Collectors.averagingDouble` are **not** used.
> Averages are calculated with `mapToDouble(...).average()`, which returns an `OptionalDouble`.

### 2a. `Collectors.groupingBy`

**File:** `src/main/java/com/amdocs/telecom/service/analytics/TicketAnalytics.java`
**Lines:** 147–149

```java
        Map<Long, List<TroubleTicket>> byEngineer = tickets.stream()
                .filter(TroubleTicket::isAssigned)
                .collect(Collectors.groupingBy(TroubleTicket::getAssignedEngineerId));
```

### 2b. `groupingBy` + `Collectors.counting`

**File:** `src/main/java/com/amdocs/telecom/service/analytics/TicketAnalytics.java`
**Lines:** 229–232

```java
        Map<SLAStatus, Long> counts = open.stream()
                .map(TroubleTicket::getSlaStatus)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
```

### 2c. `Collectors.toMap`

**File:** `src/main/java/com/amdocs/telecom/util/ReportVerification.java`
**Lines:** 448–449

```java
        Map<Long, EngineerScorecard> javaWorkload = analytics.engineerScorecards().stream()
                .collect(Collectors.toMap(EngineerScorecard::getEngineerId, card -> card));
```

### 2d. `Collectors.joining`

**File:** `src/main/java/com/amdocs/telecom/util/ReportVerification.java`
**Lines:** 1042–1044

```java
    private static <T> String labels(List<Tally<T>> tallies) {
        return tallies.stream().map(Tally::getLabel).collect(Collectors.joining(", "));
    }
```

---

## 3. Lambda Expressions

### 3a. Menu callbacks

**File:** `src/main/java/com/amdocs/telecom/controller/MainMenu.java`
**Lines:** 79–85

```java
        Menu menu = Menu.titled("TELECOM SERVICE ASSURANCE SYSTEM")
                .option("Customer Login", () -> signIn(Role.CUSTOMER))
                .option("Service Desk Login", () -> signIn(Role.SERVICE_DESK))
                .option("Network Engineer Login", () -> signIn(Role.NETWORK_ENGINEER))
                .option("Network Manager Login", () -> signIn(Role.NETWORK_MANAGER))
                .option("Forgot Password", this::forgotPassword)
                .exit("Exit", this::farewell)
```

### 3b. Named `Predicate` lambda

**File:** `src/main/java/com/amdocs/telecom/service/analytics/TicketAnalytics.java`
**Lines:** 531–537

```java
        Predicate<TroubleTicket> wanted = ticket ->
                ticket.getPriority() == Priority.CRITICAL
                        && (!openOnly || isStillOpen(ticket));
        return tickets.stream()
                .filter(wanted)
                .sorted(TroubleTicket.byUrgency())
                .collect(Collectors.toList());
```

Also see the tier predicates in section 1b above (`engineer -> engineer.hasSpecialization(required) && ...`).

---

## 4. Functional Interfaces

### 4a. `RowMapper<T>` (annotated `@FunctionalInterface`)

**File:** `src/main/java/com/amdocs/telecom/dao/RowMapper.java`
**Lines:** 16–23

```java
@FunctionalInterface
public interface RowMapper<T> {

    /**
     * Reads the row the result set is currently positioned on. Implementations
     * must not call {@code next()}.
     */
    T map(ResultSet resultSet) throws SQLException;
}
```

### 4b. `TicketEventListener` (Observer contract with a default method)

**File:** `src/main/java/com/amdocs/telecom/service/event/TicketEventListener.java`
**Lines:** 16–43

```java
public interface TicketEventListener {

    String getName();

    void onTicketEvent(TicketEvent event);

    default boolean isInterestedIn(TicketEventType type) {
        return true;
    }
}
```

*(Javadoc comments removed here for brevity.)*

### 4c. `SlaClock` (Strategy interface)

**File:** `src/main/java/com/amdocs/telecom/service/sla/SlaClock.java`
**Lines:** 26–53

```java
public interface SlaClock {

    String getName();

    String getDescription();

    LocalDateTime deadlineFrom(LocalDateTime start, int slaMinutes);

    long elapsedMinutes(LocalDateTime from, LocalDateTime to);
```

*(Javadoc comments removed here for brevity.)* `SlaClock` has more than one abstract method, so it is a strategy interface rather than a strict functional interface.

---

## 5. Optional

### 5a. Best engineer, or empty when nobody fits

**File:** `src/main/java/com/amdocs/telecom/service/assignment/EngineerRecommender.java`
**Lines:** 170–174

```java
    public Optional<EngineerMatch> best(List<NetworkEngineer> roster, Specialization required,
                                        Region region) {
        return rank(roster, required, region).stream()
                .filter(EngineerMatch::isAutomatic)
                .findFirst();
    }
```

### 5b. DAO finder methods

**File:** `src/main/java/com/amdocs/telecom/dao/impl/CustomerDAOImpl.java`
**Lines:** 109–124

```java
    public Optional<Customer> findByCustomerNumber(String customerNumber) {
        return queryOne("SELECT * FROM customers WHERE customer_number = ?", customerNumber);
    }

    @Override
    public Optional<Customer> findByEmail(String email) {
        return queryOne("SELECT * FROM customers WHERE email = ?", email);
    }

    @Override
    public Optional<Customer> findByUserId(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return queryOne("SELECT * FROM customers WHERE user_id = ?", userId);
    }
```

**File:** `src/main/java/com/amdocs/telecom/dao/impl/TroubleTicketDAOImpl.java`
**Lines:** 164–166

```java
    public Optional<TroubleTicket> findByTicketNumber(String ticketNumber) {
        return queryOne("SELECT * FROM trouble_tickets WHERE ticket_number = ?", ticketNumber);
    }
```

---

## 6. Method References

### 6a. `OpenTicketDTO::toSummaryLine` and predicate references

**File:** `src/main/java/com/amdocs/telecom/controller/ServiceDeskDashboard.java`
**Lines:** 99–110

```java
    private void viewOpen() {
        List<OpenTicketDTO> open = factory.getReportDAO().findOpenTickets();
        table("Open tickets", String.format("%-16s %-12s %-18s %-10s %-16s %-10s %-12s %s",
                        "TICKET", "CUSTOMER", "CATEGORY", "PRIORITY", "STATUS", "ENGINEER",
                        "SLA", "DUE"),
                open, OpenTicketDTO::toSummaryLine, "Nothing is open.");
        // ...
        long unassigned = open.stream().filter(OpenTicketDTO::isUnassigned).count();
        long breached = open.stream().filter(OpenTicketDTO::isBreached).count();
```

### 6b. Static method reference as a `RowMapper`

**File:** `src/main/java/com/amdocs/telecom/dao/impl/CustomerDAOImpl.java`
**Lines:** 44–47

```java
    @Override
    protected RowMapper<Customer> mapper() {
        return CustomerDAOImpl::mapRow;
    }
```

---

## 7. Default and Static Interface Methods

### 7a. `Displayable`

**File:** `src/main/java/com/amdocs/telecom/model/Displayable.java`
**Lines:** 17–47

```java
public interface Displayable {

    String toSummaryLine();

    default String toDetailBlock() {
        return toSummaryLine();
    }

    static String formatDateTime(LocalDateTime value) {
        return value == null ? "-" : value.format(AppConstants.DISPLAY_DATE_TIME);
    }

    static String formatDate(LocalDate value) {
        return value == null ? "-" : value.format(AppConstants.DISPLAY_DATE);
    }
```

### 7b. `DescribableEnum`

**File:** `src/main/java/com/amdocs/telecom/model/enums/DescribableEnum.java`
**Lines:** 18–52

```java
public interface DescribableEnum {

    String getCode();

    String getDisplayName();

    default String describe() {
        return getCode() + " - " + getDisplayName();
    }

    static <E extends Enum<E> & DescribableEnum> Optional<E> fromCode(Class<E> enumType, String code) {
        if (code == null || code.trim().isEmpty()) {
            return Optional.empty();
        }
        final String target = code.trim();
        return Arrays.stream(enumType.getEnumConstants())
                .filter(constant -> constant.getCode().equalsIgnoreCase(target))
                .findFirst();
    }
```

### 7c. `SlaClock`

**File:** `src/main/java/com/amdocs/telecom/service/sla/SlaClock.java`
**Lines:** 75–94

```java
    default double consumedFraction(LocalDateTime start, LocalDateTime deadline,
                                    LocalDateTime upTo) {
        long window = elapsedMinutes(start, deadline);
        if (window <= 0L) {
            return 1.0d;
        }
        return (double) elapsedMinutes(start, upTo) / (double) window;
    }

    static LocalDateTime requireMoment(LocalDateTime value, String what) {
        if (value == null) {
            throw new IllegalArgumentException(what + " must not be null");
        }
        return value;
    }
```

---

## 8. Comparator Chaining

### 8a. Workload, then experience, then code

**File:** `src/main/java/com/amdocs/telecom/model/NetworkEngineer.java`
**Lines:** 166–173

```java
    public static Comparator<NetworkEngineer> byWorkloadThenExperience() {
        Comparator<NetworkEngineer> byWorkload =
                Comparator.comparingInt(NetworkEngineer::getActiveTicketCount);
        Comparator<NetworkEngineer> byExperience =
                Comparator.comparingInt(NetworkEngineer::getExperienceYears);
        Comparator<NetworkEngineer> byCode =
                Comparator.comparing(NetworkEngineer::getEmployeeCode);
        return byWorkload.thenComparing(byExperience.reversed()).thenComparing(byCode);
    }
```

### 8b. `OpenTicketDTO.byUrgency()`

**File:** `src/main/java/com/amdocs/telecom/dto/OpenTicketDTO.java`
**Lines:** 183–189

```java
    public static Comparator<OpenTicketDTO> byUrgency() {
        Comparator<OpenTicketDTO> byWeight =
                Comparator.comparingInt(OpenTicketDTO::getPriorityWeight);
        Comparator<OpenTicketDTO> byAge = Comparator.comparing(
                OpenTicketDTO::getCreatedDate,
                Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()));
        return byWeight.reversed().thenComparing(byAge);
    }
```

### 8c. Tier first, then the engineer comparator

**File:** `src/main/java/com/amdocs/telecom/service/assignment/EngineerMatch.java`
**Lines:** 65–68

```java
    public static Comparator<EngineerMatch> preferredOrder() {
        Comparator<EngineerMatch> byTier = Comparator.comparing(EngineerMatch::getTier);
        return byTier.thenComparing(EngineerMatch::getEngineer,
                NetworkEngineer.byWorkloadThenExperience());
    }
```

---

## 9. Date/Time API

### 9a. `LocalDateTime` + `ChronoUnit` (business-hours SLA deadline)

**File:** `src/main/java/com/amdocs/telecom/service/sla/BusinessHoursSlaClock.java`
**Lines:** 93–107

```java
    public LocalDateTime deadlineFrom(LocalDateTime start, int slaMinutes) {
        SlaClock.requireMoment(start, "The moment an SLA window opens");
        SlaClock.requireMinutes(slaMinutes);

        LocalDateTime cursor = nextWorkingMoment(start);
        long remaining = slaMinutes;

        while (remaining > 0L) {
            LocalDateTime closingTime = LocalDateTime.of(cursor.toLocalDate(), closes);
            long availableToday = ChronoUnit.MINUTES.between(cursor, closingTime);
            if (availableToday >= remaining) {
                return cursor.plusMinutes(remaining);
            }
            remaining -= availableToday;
            cursor = nextWorkingMoment(LocalDateTime.of(cursor.toLocalDate().plusDays(1), opens));
        }
```

### 9b. 24/7 clock

**File:** `src/main/java/com/amdocs/telecom/service/sla/ContinuousSlaClock.java`
**Lines:** 50–56

```java
    public long elapsedMinutes(LocalDateTime from, LocalDateTime to) {
        SlaClock.requireMoment(from, "The start of an elapsed SLA period");
        SlaClock.requireMoment(to, "The end of an elapsed SLA period");
        if (!to.isAfter(from)) {
            return 0L;
        }
        return ChronoUnit.MINUTES.between(from, to);
    }
```

### 9c. `Duration.between` (session idle timeout)

**File:** `src/main/java/com/amdocs/telecom/security/UserSession.java`
**Lines:** 108–110

```java
    public boolean isIdleTooLong() {
        return Duration.between(lastActivity, LocalDateTime.now()).toMinutes()
                >= AppConstants.SESSION_IDLE_MINUTES;
    }
```

---

## 10. The Nine Java 8 Analyses (section 16)

**File:** `src/main/java/com/amdocs/telecom/service/analytics/TicketAnalytics.java`

| # | Analysis | Method | Lines |
|---|---|---|---|
| 1 | Tickets by status | `public List<Tally<TicketStatus>> byStatus()` | 124–127 |
| 2 | Tickets by priority | `public List<Tally<Priority>> byPriority()` | 131–133 |
| 3 | Engineer workload | `public List<EngineerScorecard> engineerWorkload()` | 162–164 |
| 4 | SLA breach analysis | `public List<SlaOutcome> slaBreachAnalysis()` | 192–204 |
| 5 | Average resolution time | `public OptionalDouble averageResolutionHours()` | 253–259 |
| 6 | Tickets by region | `public List<Tally<Region>> byRegion()` | 332–334 |
| 7 | Top incident categories | `public List<Tally<IncidentCategory>> topCategories(int limit)` | 381–389 |
| 8 | Engineer performance | `public List<EngineerScorecard> engineerPerformance()` | 167–171 |
| 9 | Customers with repeated incidents | `public List<RepeatCustomer> repeatIncidents(int threshold)` | 400–419 |

**Example — SLA breach analysis (lines 192–204):**

```java
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
```

**Example — repeat incidents (lines 400–419):**

```java
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
```

---

# PART B — MULTITHREADING AND SYNCHRONIZATION (Case study sections 11 and 17)

### The worker classes

| Worker | File | Class declared at line |
|---|---|---|
| `NetworkEventProcessor` (queue consumer) | `src/main/java/com/amdocs/telecom/scheduler/NetworkEventProcessor.java` | 56 |
| `NetworkEventFeeder` (queue producer) | `src/main/java/com/amdocs/telecom/scheduler/NetworkEventFeeder.java` | 32 |
| `SlaMonitor` | `src/main/java/com/amdocs/telecom/scheduler/SlaMonitor.java` | 59 |
| `NotificationProcessor` | `src/main/java/com/amdocs/telecom/scheduler/NotificationProcessor.java` | 48 |
| `ReportGenerator` | `src/main/java/com/amdocs/telecom/report/ReportGenerator.java` | 55 |
| `BackgroundWorker` (shared interface) | `src/main/java/com/amdocs/telecom/scheduler/BackgroundWorker.java` | 17 |
| `BackgroundServices` (starts and stops all of them) | `src/main/java/com/amdocs/telecom/scheduler/BackgroundServices.java` | — |

## 11. ExecutorService

### 11a. Fixed thread pool + `submit` (alarm consumers)

**File:** `src/main/java/com/amdocs/telecom/scheduler/NetworkEventProcessor.java`
**Lines:** 136–147

```java
    @Override
    public synchronized void start() {
        if (running) {
            AppLogger.warn(NetworkEventProcessor.class,
                    "The alarm processor is already running; ignoring the second start");
            return;
        }
        running = true;
        consumers = Executors.newFixedThreadPool(consumerCount, new NamedThreads(WORKER_NAME));
        for (int index = 0; index < consumerCount; index++) {
            consumers.submit(this::consume);
        }
```

### 11b. Report thread pool

**File:** `src/main/java/com/amdocs/telecom/report/ReportGenerator.java`
**Lines:** 125–135

```java
    @Override
    public synchronized void start() {
        if (running) {
            AppLogger.warn(ReportGenerator.class,
                    "The report generator is already running; ignoring the second start");
            return;
        }
        running = true;
        pool = Executors.newFixedThreadPool(threadCount, new NamedThreads(WORKER_NAME));
        AppLogger.info(ReportGenerator.class, "Report generator started with "
                + threadCount + " thread(s)");
    }
```

---

## 12. ScheduledExecutorService (`scheduleAtFixedRate`)

### 12a. SLA monitor

**File:** `src/main/java/com/amdocs/telecom/scheduler/SlaMonitor.java`
**Lines:** 120–130

```java
    public synchronized void start() {
        if (running) {
            AppLogger.warn(SlaMonitor.class,
                    "The SLA monitor is already running; ignoring the second start");
            return;
        }
        running = true;
        schedule = Executors.newSingleThreadScheduledExecutor(new NamedThreads(WORKER_NAME));
        // Runs once immediately so a freshly started application does not
        // wait a whole period before noticing a ticket that is already late.
        schedule.scheduleAtFixedRate(this::sweep, 0L, periodSeconds, TimeUnit.SECONDS);
```

### 12b. Notification processor

**File:** `src/main/java/com/amdocs/telecom/scheduler/NotificationProcessor.java`
**Lines:** 103–112

```java
    public synchronized void start() {
        if (running) {
            AppLogger.warn(NotificationProcessor.class,
                    "The notification processor is already running; ignoring the second start");
            return;
        }
        running = true;
        schedule = Executors.newSingleThreadScheduledExecutor(new NamedThreads(WORKER_NAME));
        schedule.scheduleAtFixedRate(this::dispatchNow, periodSeconds, periodSeconds,
                TimeUnit.SECONDS);
```

### 12c. Alarm feeder (producer)

**File:** `src/main/java/com/amdocs/telecom/scheduler/NetworkEventFeeder.java`
**Lines:** 80–88

```java
    public synchronized void start() {
        if (running) {
            AppLogger.warn(NetworkEventFeeder.class,
                    "The alarm feeder is already running; ignoring the second start");
            return;
        }
        running = true;
        schedule = Executors.newSingleThreadScheduledExecutor(new NamedThreads(WORKER_NAME));
        schedule.scheduleAtFixedRate(this::burst, periodSeconds, periodSeconds, TimeUnit.SECONDS);
```

---

## 13. BlockingQueue — Producer / Consumer

Flow: `NetworkEventFeeder` (producer) → `ArrayBlockingQueue` → `NetworkEventProcessor` (consumer threads).
The producer uses `offer` and the consumer uses `poll` with a timeout, so a full queue never blocks the producer and a consumer can notice `stop()`.

### 13a. Creating the bounded queue

**File:** `src/main/java/com/amdocs/telecom/scheduler/NetworkEventProcessor.java`
**Lines:** 117–119

```java
        this.handler = handler;
        this.consumerCount = consumerCount;
        this.queue = new ArrayBlockingQueue<NetworkEvent>(capacity);
```

### 13b. Producer side — `offer`

**File:** `src/main/java/com/amdocs/telecom/scheduler/NetworkEventProcessor.java`
**Lines:** 194–206

```java
    public boolean offer(NetworkEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("An alarm is required");
        }
        synchronized (pendingLock) {
            if (!queue.offer(event)) {
                rejected.incrementAndGet();
                return false;
            }
            pending++;
        }
        accepted.incrementAndGet();
        return true;
    }
```

### 13c. Consumer side — `poll`

**File:** `src/main/java/com/amdocs/telecom/scheduler/NetworkEventProcessor.java`
**Lines:** 230–246

```java
    private void consume() {
        AppLogger.debug(NetworkEventProcessor.class, "Alarm consumer ready");
        try {
            while (running || !queue.isEmpty()) {
                NetworkEvent event = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (event == null) {
                    continue;
                }
                handleOne(event);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            AppLogger.debug(NetworkEventProcessor.class, "Alarm consumer interrupted; stopping");
        }
    }
```

---

## 14. Runnable

> Note: `TRACEABILITY.md` names `BackgroundWorker` for `Runnable`, but
> `BackgroundWorker` is a plain lifecycle interface (`start`, `stop`,
> `isRunning`, `describe`). The `Runnable` usages are the ones below.

### 14a. Method reference submitted as a `Runnable`

**File:** `src/main/java/com/amdocs/telecom/scheduler/NetworkEventProcessor.java`
**Line:** 146 — `consumers.submit(this::consume);`
The `consume()` loop shown in 13c is the `Runnable` body that each pool thread runs.

### 14b. `ThreadFactory` wraps each `Runnable` in a named thread

**File:** `src/main/java/com/amdocs/telecom/scheduler/NamedThreads.java`
**Lines:** 41–42

```java
    public Thread newThread(Runnable work) {
        Thread thread = new Thread(work, prefix + "-" + counter.incrementAndGet());
```

### 14c. Shutdown hook thread built from a `Runnable`

**File:** `src/main/java/com/amdocs/telecom/scheduler/BackgroundServices.java`
**Lines:** 115–117

```java
    public void stopOnExit() {
        Runtime.getRuntime().addShutdownHook(
                new Thread(this::stopAll, "tsatms-shutdown"));
```

---

## 15. Callable and Future

### 15a. `Callable` task (lambda)

**File:** `src/main/java/com/amdocs/telecom/report/ReportGenerator.java`
**Lines:** 257–276

```java
    private Callable<ReportResult> taskFor(ReportKind kind) {
        return () -> {
            long startedAt = System.nanoTime();
            try {
                ReportTable table = source.build(kind);
                Duration took = Duration.ofNanos(System.nanoTime() - startedAt);
                produced.incrementAndGet();
                rowsTotal.addAndGet(table.getRowCount());
                return new ReportResult(table, LocalDateTime.now(), took,
                        Thread.currentThread().getName());
            } catch (RuntimeException failure) {
                failed.incrementAndGet();
                AppLogger.error(ReportGenerator.class,
                        "Report " + kind.getDisplayName() + " failed", failure);
                throw failure;
            }
        };
    }
```

### 15b. `submit` returns a `Future`

**File:** `src/main/java/com/amdocs/telecom/report/ReportGenerator.java`
**Lines:** 178–195

```java
    public Future<ReportResult> submit(ReportKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("A report kind is required");
        }
        ExecutorService current = pool;
        if (!running || current == null) {
            throw new IllegalStateException(
                    "The report generator is not running; start it before submitting reports");
        }
        submitted.incrementAndGet();
        try {
            return current.submit(taskFor(kind));
        } catch (RejectedExecutionException rejected) {
            submitted.decrementAndGet();
            throw new IllegalStateException(
                    "The report generator is shutting down and cannot take " + kind.getDisplayName(),
                    rejected);
        }
    }
```

### 15c. `invokeAll` — run many `Callable`s and wait for all

**File:** `src/main/java/com/amdocs/telecom/report/ReportGenerator.java`
**Lines:** 222–235

```java
        List<ReportKind> ordered = new ArrayList<ReportKind>(kinds);
        List<Callable<ReportResult>> tasks =
                new ArrayList<Callable<ReportResult>>(ordered.size());
        for (ReportKind kind : ordered) {
            tasks.add(taskFor(kind));
        }
        submitted.addAndGet(tasks.size());

        try {
            List<Future<ReportResult>> futures = current.invokeAll(tasks);
            for (int index = 0; index < futures.size(); index++) {
                ReportKind kind = ordered.get(index);
                try {
                    answers.put(kind, futures.get(index).get());
```

---

## 16. Synchronization

> Not used in this project: `ReentrantLock`, `ReadWriteLock`, `AtomicBoolean`.

### 16a. `synchronized` methods

| File | Lines | Methods |
|---|---|---|
| `src/main/java/com/amdocs/telecom/scheduler/BackgroundServices.java` | 74, 94 | `startAll()`, `stopAll()` |
| `src/main/java/com/amdocs/telecom/scheduler/NetworkEventFeeder.java` | 80, 94 | `start()`, `stop()` |
| `src/main/java/com/amdocs/telecom/scheduler/NetworkEventProcessor.java` | 137, 153 | `start()`, `stop()` |
| `src/main/java/com/amdocs/telecom/scheduler/SlaMonitor.java` | 120, 136 | `start()`, `stop()` |
| `src/main/java/com/amdocs/telecom/scheduler/NotificationProcessor.java` | 103, 118 | `start()`, `stop()` |
| `src/main/java/com/amdocs/telecom/report/ReportGenerator.java` | 126, 139 | `start()`, `stop()` |
| `src/main/java/com/amdocs/telecom/util/AppLogger.java` | 46, 191 | `initialize()`, `shutdown()` |

**Example — `src/main/java/com/amdocs/telecom/scheduler/BackgroundServices.java`, lines 74–85:**

```java
    public synchronized void startAll() {
        if (started) {
            AppLogger.warn(BackgroundServices.class, "Background services are already running");
            return;
        }
        reportGenerator.start();
        notificationProcessor.start();
        slaMonitor.start();
        alarmProcessor.start();
        alarmFeeder.start();
        started = true;
        AppLogger.info(BackgroundServices.class, "All background services are running");
    }
```

### 16b. `synchronized` block with `wait()` / `notifyAll()`

**File:** `src/main/java/com/amdocs/telecom/scheduler/NetworkEventProcessor.java`
**Lines:** 306–317 (waiting side)

```java
    public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (pendingLock) {
            while (pending > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                pendingLock.wait(remaining);
            }
            return true;
        }
    }
```

**Same file, lines 265–270 (notifying side, inside `handleOne`):**

```java
        } finally {
            completed.incrementAndGet();
            synchronized (pendingLock) {
                pending--;
                pendingLock.notifyAll();
            }
        }
```

### 16c. `synchronized` block on a shared set

**File:** `src/main/java/com/amdocs/telecom/scheduler/NotificationProcessor.java`
**Lines:** 187–198 (the set is created with `Collections.synchronizedSet(new HashSet<>())` at line 68)

```java
    private boolean claim(Long notificationId) {
        if (notificationId == null) {
            return false;
        }
        synchronized (sent) {
            if (sent.size() >= MEMORY_LIMIT) {
                sent.clear();
                AppLogger.debug(NotificationProcessor.class,
                        "Cleared the delivery memory after " + MEMORY_LIMIT + " message(s)");
            }
            return sent.add(notificationId);
        }
    }
```

### 16d. `volatile` fields

| File | Line | Field |
|---|---|---|
| `src/main/java/com/amdocs/telecom/scheduler/BackgroundServices.java` | 50 | `private volatile boolean started;` |
| `src/main/java/com/amdocs/telecom/scheduler/NetworkEventFeeder.java` | 50 | `running` |
| `src/main/java/com/amdocs/telecom/scheduler/NetworkEventProcessor.java` | 92 | `running` |
| `src/main/java/com/amdocs/telecom/scheduler/SlaMonitor.java` | 82 | `running` |
| `src/main/java/com/amdocs/telecom/scheduler/NotificationProcessor.java` | 75 | `running` |
| `src/main/java/com/amdocs/telecom/report/ReportGenerator.java` | 84 | `running` |
| `src/main/java/com/amdocs/telecom/security/SessionContext.java` | 28 | `private static volatile UserSession current;` |
| `src/main/java/com/amdocs/telecom/util/AppLogger.java` | 34 | `initialised` |
| `src/main/java/com/amdocs/telecom/service/impl/SlaServiceImpl.java` | 58 | `cachedWindows` |

### 16e. Atomic counters (`AtomicLong`)

**File:** `src/main/java/com/amdocs/telecom/scheduler/NetworkEventProcessor.java`
**Lines:** 78–85

```java
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong ticketsRaised = new AtomicLong();
    private final AtomicLong correlated = new AtomicLong();
    private final AtomicLong ignored = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong lostClaims = new AtomicLong();
```

Also in `NetworkEventFeeder.java` (45–48), `SlaMonitor.java` (75–80), `NotificationProcessor.java` (70–73), `ReportGenerator.java` (79–82).

### 16f. `AtomicInteger` + `ConcurrentHashMap.putIfAbsent` (thread-safe ticket numbers)

**File:** `src/main/java/com/amdocs/telecom/service/impl/TicketNumberGenerator.java`
**Lines:** 89–99

```java
    private AtomicInteger counterFor(int year) {
        AtomicInteger counter = sequenceByYear.get(year);
        if (counter != null) {
            return counter;
        }
        AtomicInteger seeded = new AtomicInteger(tickets.findHighestSequenceForYear(year));
        AtomicInteger existing = sequenceByYear.putIfAbsent(year, seeded);
        return existing == null ? seeded : existing;
    }
```

### 16g. `ConcurrentHashMap`

**File:** `src/main/java/com/amdocs/telecom/scheduler/SlaMonitor.java`
**Lines:** 72–73

```java
    /** The standing last announced per ticket, so nothing is said twice. */
    private final Map<Long, SLAStatus> announced = new ConcurrentHashMap<Long, SLAStatus>();
```

Also used in `service/impl/TicketNumberGenerator.java` (45–46) and `security/OtpService.java` (26).

### 16h. `CountDownLatch` (test harness only)

**File:** `src/main/java/com/amdocs/telecom/util/ThreadVerification.java`
**Lines:** 655–657

```java
    private static void verifyFutureSemantics() {
        final CountDownLatch hold = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
```

---

## 17. Named Threads (`ThreadFactory`)

**File:** `src/main/java/com/amdocs/telecom/scheduler/NamedThreads.java`
**Lines:** 24–50

```java
public final class NamedThreads implements ThreadFactory {

    private final String prefix;
    private final AtomicInteger counter = new AtomicInteger();

    public NamedThreads(String prefix) {
        if (prefix == null || prefix.trim().isEmpty()) {
            throw new IllegalArgumentException("A thread name prefix is required");
        }
        this.prefix = prefix.trim();
    }

    @Override
    public Thread newThread(Runnable work) {
        Thread thread = new Thread(work, prefix + "-" + counter.incrementAndGet());
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((who, failure) ->
                AppLogger.error(NamedThreads.class,
                        "Worker thread '" + who.getName() + "' died of an uncaught exception",
                        failure instanceof Exception ? (Exception) failure
                                : new IllegalStateException(failure)));
        return thread;
    }
}
```

---

## 18. Clean Shutdown and Interrupt Handling

### 18a. `shutdown` → `awaitTermination` → `shutdownNow`, restoring the interrupt flag

**File:** `src/main/java/com/amdocs/telecom/scheduler/NetworkEventProcessor.java`
**Lines:** 153–177

```java
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        ExecutorService pool = consumers;
        consumers = null;
        if (pool == null) {
            return;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
                AppLogger.warn(NetworkEventProcessor.class, "Alarm consumers did not stop within "
                        + STOP_GRACE_SECONDS + "s; interrupting them");
                pool.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        AppLogger.info(NetworkEventProcessor.class, "Alarm processor stopped. " + describe());
    }
```

The same pattern is in `SlaMonitor.stop()` — `src/main/java/com/amdocs/telecom/scheduler/SlaMonitor.java`, lines 136–154.

### 18b. Stopping everything, plus a JVM shutdown hook

**File:** `src/main/java/com/amdocs/telecom/scheduler/BackgroundServices.java`
**Lines:** 94–117

```java
    public synchronized void stopAll() {
        if (!started) {
            return;
        }
        alarmFeeder.stop();
        alarmProcessor.stop();
        slaMonitor.stop();
        notificationProcessor.stop();
        reportGenerator.stop();
        started = false;
        AppLogger.info(BackgroundServices.class, "All background services have stopped");
    }
    // ...
    public void stopOnExit() {
        Runtime.getRuntime().addShutdownHook(
                new Thread(this::stopAll, "tsatms-shutdown"));
    }
```

---

## 19. ThreadLocal (one transaction per thread)

**File:** `src/main/java/com/amdocs/telecom/dao/ConnectionScope.java`
**Lines:** 22–81 (key parts)

```java
public final class ConnectionScope implements AutoCloseable {

    private static final ThreadLocal<Connection> TRANSACTIONAL = new ThreadLocal<>();
    // ...
    public static ConnectionScope open() {
        Connection active = TRANSACTIONAL.get();
        if (active != null) {
            return new ConnectionScope(active, false);
        }
        return new ConnectionScope(DBConnection.getInstance().getConnection(), true);
    }
    // ...
    static void bind(Connection connection) {
        TRANSACTIONAL.set(connection);
    }

    static void unbind() {
        TRANSACTIONAL.remove();
    }
}
```

`TransactionTemplate.executeNew()` calls `ConnectionScope.bind(connection)` at line 78 of `src/main/java/com/amdocs/telecom/dao/TransactionTemplate.java`.

---

## 20. PriorityQueue (escalation, section 9)

### 20a. Building the queue

**File:** `src/main/java/com/amdocs/telecom/service/impl/EscalationServiceImpl.java`
**Lines:** 152–163

```java
    private Queue<EscalationCandidate> queueOfCandidates() {
        List<TroubleTicket> open = tickets.findOpen();
        Queue<EscalationCandidate> queue =
                new PriorityQueue<EscalationCandidate>(Math.max(open.size(), 1),
                        EscalationCandidate.mostUrgentFirst());
        for (TroubleTicket ticket : open) {
            Optional<EscalationCandidate> candidate = candidateFor(ticket);
            if (candidate.isPresent()) {
                queue.offer(candidate.get());
            }
        }
        return queue;
    }
```

### 20b. Draining in priority order with `poll`

**File:** `src/main/java/com/amdocs/telecom/service/impl/EscalationServiceImpl.java`
**Lines:** 171–176

```java
    private static List<EscalationCandidate> drain(Queue<EscalationCandidate> queue, int limit) {
        List<EscalationCandidate> ordered = new ArrayList<EscalationCandidate>();
        while (!queue.isEmpty() && ordered.size() < limit) {
            ordered.add(queue.poll());
        }
        return ordered;
    }
```

### 20c. The ordering comparator

**File:** `src/main/java/com/amdocs/telecom/service/escalation/EscalationCandidate.java`
**Lines:** 53–55

```java
    public static Comparator<EscalationCandidate> mostUrgentFirst() {
        return Comparator.comparing(EscalationCandidate::getTicket, TroubleTicket.byUrgency());
    }
```

---

## 21. Database-Level Concurrency Guard (only one thread claims an event)

### 21a. Service — claim first

**File:** `src/main/java/com/amdocs/telecom/service/impl/NetworkEventServiceImpl.java`
**Lines:** 169–177

```java
    private EventOutcome decide(NetworkEvent event) {
        String reference = event.getEventReference();

        // Claimed before anything is read, so two consumers holding the same
        // alarm cannot both go on to raise a ticket for it. The loser is told
        // it lost rather than being failed: nothing went wrong.
        if (!events.claimForProcessing(event.getId(), EventStatus.RECEIVED)) {
            return EventOutcome.alreadyClaimed(reference);
        }
```

### 21b. DAO — conditional `UPDATE`

**File:** `src/main/java/com/amdocs/telecom/dao/impl/NetworkEventDAOImpl.java`
**Lines:** 161–166

```java
    @Override
    public boolean claimForProcessing(Long eventId, EventStatus expectedStatus) {
        return executeUpdate("UPDATE network_events SET event_status = ? "
                        + "WHERE event_id = ? AND event_status = ?",
                EventStatus.PROCESSING, eventId, expectedStatus) > 0;
    }
```

---

# PART C — EXCEPTION HANDLING

## 22. Custom Exception Hierarchy

All are in `src/main/java/com/amdocs/telecom/exception/`.

| Class | File | Line | Extends |
|---|---|---|---|
| `TSATMSException` (root) | `TSATMSException.java` | 11 | `RuntimeException` |
| `DataAccessException` | `DataAccessException.java` | 7 | `TSATMSException` |
| `ValidationException` | `ValidationException.java` | 13 | `TSATMSException` |
| `BusinessException` | `BusinessException.java` | 7 | `TSATMSException` |
| `AuthenticationException` | `AuthenticationException.java` | 7 | `TSATMSException` |
| `AuthorizationException` | `AuthorizationException.java` | 7 | `TSATMSException` |
| `ConfigurationException` | `ConfigurationException.java` | 6 | `TSATMSException` |
| `ResourceNotFoundException` | `ResourceNotFoundException.java` | 6 | `BusinessException` |
| `DuplicateResourceException` | `DuplicateResourceException.java` | 7 | `BusinessException` |

**Root class — `src/main/java/com/amdocs/telecom/exception/TSATMSException.java`, lines 11–46:**

```java
public class TSATMSException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public TSATMSException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public TSATMSException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public TSATMSException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public TSATMSException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String toDisplayString() {
        return "[" + errorCode.getCode() + "] " + getMessage();
    }
```

---

## 23. try / catch / finally

### 23a. Several catch blocks + finally (transaction)

**File:** `src/main/java/com/amdocs/telecom/dao/TransactionTemplate.java`
**Lines:** 71–107

```java
    private static <T> T executeNew(TransactionCallback<T> callback) {
        Connection connection = DBConnection.getInstance().getConnection();
        boolean previousAutoCommit = true;
        boolean committed = false;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            ConnectionScope.bind(connection);

            T result = callback.doInTransaction(new JdbcTransactionContext(connection));

            connection.commit();
            committed = true;
            AppLogger.debug(TransactionTemplate.class, "Transaction committed");
            return result;
        } catch (SQLException cause) {
            DBConnection.rollbackQuietly(connection);
            throw new DataAccessException(ErrorCode.DB_TRANSACTION_FAILED,
                    "Transaction failed and was rolled back: " + cause.getMessage(), cause);
        } catch (TSATMSException cause) {
            DBConnection.rollbackQuietly(connection);
            AppLogger.warn(TransactionTemplate.class,
                    "Transaction rolled back: " + cause.toDisplayString());
            throw cause;
        } catch (RuntimeException cause) {
            DBConnection.rollbackQuietly(connection);
            throw new DataAccessException(ErrorCode.DB_TRANSACTION_FAILED,
                    "Transaction rolled back after an unexpected error: " + cause.getMessage(), cause);
        } finally {
            ConnectionScope.unbind();
            if (!committed) {
                DBConnection.rollbackQuietly(connection);
            }
            DBConnection.restoreAutoCommit(connection, previousAutoCommit);
            DBConnection.closeQuietly(connection);
        }
    }
```

### 23b. try / finally (wiping the password)

**File:** `src/main/java/com/amdocs/telecom/service/impl/AuthenticationServiceImpl.java`
**Lines:** 85–93

```java
    public LoginResult authenticate(String username, char[] password,
                                    CaptchaChallenge captcha, String captchaResponse) {
        try {
            return runAuthentication(username, password, captcha, captchaResponse);
        } finally {
            // The caller may forget; this class will not.
            ConsoleReader.clear(password);
        }
    }
```

---

## 24. Multi-catch (`catch (A | B e)`)

**File:** `src/main/java/com/amdocs/telecom/security/PasswordHasher.java`
**Lines:** 150–159

```java
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(algorithm);
            return toHex(factory.generateSecret(specification).getEncoded());
        } catch (NoSuchAlgorithmException | InvalidKeySpecException cause) {
            throw new TSATMSException(ErrorCode.UNEXPECTED_ERROR,
                    "Password hashing failed using " + algorithm, cause);
        } finally {
            // Clears the copy PBEKeySpec made of the password.
            specification.clearPassword();
        }
```

**File:** `src/main/java/com/amdocs/telecom/util/DBConnection.java`
**Lines:** 137–143

```java
    public boolean isServerReachable() {
        try (Connection connection = getAdminConnection()) {
            return connection.isValid(validationTimeoutSeconds);
        } catch (SQLException | DataAccessException cause) {
            AppLogger.debug(DBConnection.class, "Server reachability check failed: " + cause.getMessage());
            return false;
        }
    }
```

Also in `src/main/java/com/amdocs/telecom/util/DatabaseBootstrap.java`, line 253: `catch (SQLException | TSATMSException failure)`.

---

## 25. try-with-resources

### 25a. Connection + PreparedStatement + ResultSet

**File:** `src/main/java/com/amdocs/telecom/dao/impl/JdbcOperations.java`
**Lines:** 47–60

```java
    protected <R> List<R> query(String sql, RowMapper<R> rowMapper, Object... parameters) {
        try (ConnectionScope scope = ConnectionScope.open();
             PreparedStatement statement = scope.connection().prepareStatement(sql)) {
            JdbcSupport.bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<R> results = new ArrayList<>();
                while (resultSet.next()) {
                    results.add(rowMapper.map(resultSet));
                }
                return results;
            }
        } catch (SQLException cause) {
            throw failure(ErrorCode.DB_QUERY_FAILED, "Query failed", sql, parameters, cause);
        }
    }
```

### 25b. File writer

**File:** `src/main/java/com/amdocs/telecom/report/ReportExporter.java`
**Lines:** 79–89

```java
        try {
            Files.createDirectories(directory);
            // try-with-resources: the file handle is released whether the
            // write succeeds or throws halfway through a large report.
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write(table, out);
            }
        } catch (IOException failure) {
            throw new TSATMSException(ErrorCode.FILE_WRITE_FAILED,
                    "Could not write " + table.getKind().getDisplayName() + " to " + file,
                    failure);
        }
```

Also: `src/main/java/com/amdocs/telecom/util/DBConnection.java`, lines 161–172 (`schemaExists()`).

---

## 26. Exception Wrapping / Translation

### 26a. `SQLException` → `DuplicateResourceException` or `DataAccessException`

**File:** `src/main/java/com/amdocs/telecom/dao/impl/JdbcOperations.java`
**Lines:** 176–191

```java
    protected RuntimeException failure(ErrorCode errorCode, String what, String sql,
                                       Object[] parameters, SQLException cause) {
        String description = what + " on " + sourceName() + ": " + cause.getMessage()
                + " [sql=" + JdbcSupport.abbreviate(sql) + "]";

        if (cause.getErrorCode() == MYSQL_DUPLICATE_ENTRY) {
            AppLogger.warn(getClass(),
                    sourceName() + " rejected a duplicate value: " + cause.getMessage());
            return new DuplicateResourceException(
                    sourceName() + " rejected a duplicate value: " + cause.getMessage());
        }

        AppLogger.error(getClass(), description
                + (parameters == null ? "" : " params=" + Arrays.toString(parameters)), cause);
        return new DataAccessException(errorCode, description, cause);
    }
```

### 26b. `SQLException` → `DataAccessException` when connecting

**File:** `src/main/java/com/amdocs/telecom/util/DBConnection.java`
**Lines:** 104–114

```java
    public Connection getConnection() {
        try {
            Connection connection = DriverManager.getConnection(
                    getApplicationUrl(), applicationUser, applicationPassword);
            connection.setAutoCommit(defaultAutoCommit);
            return connection;
        } catch (SQLException cause) {
            throw new DataAccessException(ErrorCode.DB_CONNECTION_FAILED,
                    "Could not connect to " + schema + " as '" + applicationUser + "': " + cause.getMessage(),
                    cause);
        }
    }
```

### 26c. `IOException` → `ConfigurationException`

**File:** `src/main/java/com/amdocs/telecom/util/ConfigLoader.java`
**Lines:** 63–70

```java
    private static void readFrom(Properties target, File file) {
        try (InputStream input = new FileInputStream(file);
             Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            target.load(reader);
        } catch (IOException cause) {
            throw new ConfigurationException(ErrorCode.CONFIG_LOAD_FAILED,
                    "Could not read configuration file " + file.getAbsolutePath(), cause);
        }
    }
```

---

## 27. Throwing Custom Exceptions for Business and Validation Rules

### 27a. `ValidationException` and `BusinessException`

**File:** `src/main/java/com/amdocs/telecom/service/impl/TicketServiceImpl.java`
**Lines:** 338–351

```java
    public void changeStatus(UserSession actor, String ticketNumber, TicketStatus target,
                             String remarks) {
        if (target == null) {
            throw new ValidationException("A target status is required");
        }
        if (!DIRECTLY_SETTABLE.contains(target)) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    describeWrongDoor(target));
        }
        TicketValidator.validateRemarks(remarks);

        TroubleTicket ticket = authorise(actor, ticketNumber, Permission.UPDATE_TICKET_STATUS);
        TicketStatus from = ticket.getStatus();
        TicketLifecycle.requireTransition(ticketNumber, from, target);
```

### 27b. Illegal status transition

**File:** `src/main/java/com/amdocs/telecom/validation/TicketLifecycle.java`
**Lines:** 106–113

```java
    public static void requireTransition(String ticketNumber, TicketStatus from,
                                         TicketStatus to) {
        if (canMove(from, to)) {
            return;
        }
        throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                describeRefusal(ticketNumber, from, to));
    }
```

### 27c. `AuthenticationException` and `AuthorizationException`

**File:** `src/main/java/com/amdocs/telecom/security/AccessControl.java`
**Lines:** 56–74

```java
    public static void require(UserSession session, Permission permission) {
        if (session == null) {
            throw new AuthenticationException(ErrorCode.AUTH_SESSION_EXPIRED,
                    "You must sign in before performing this operation");
        }
        if (session.isSignedOut()) {
            throw new AuthenticationException(ErrorCode.AUTH_SESSION_EXPIRED,
                    "This session has been signed out. Please sign in again.");
        }
        if (session.isIdleTooLong()) {
            throw new AuthenticationException(ErrorCode.AUTH_SESSION_EXPIRED,
                    "This session has been idle too long. Please sign in again.");
        }
        if (!isPermitted(session.getRole(), permission)) {
            AppLogger.warn(AccessControl.class, "Access denied: '" + session.getUsername()
                    + "' as " + session.getRole() + " attempted " + permission.name());
            throw new AuthorizationException("A " + session.getRole().getDisplayName()
                    + " may not " + lowerFirst(permission.getDescription()));
        }
        session.touch();
    }
```

---

## 28. Log-and-Skip: One Bad Item Never Kills a Worker

### 28a. Alarm consumer

**File:** `src/main/java/com/amdocs/telecom/scheduler/NetworkEventProcessor.java`
**Lines:** 257–271

```java
    private void handleOne(NetworkEvent event) {
        try {
            EventOutcome outcome = handler.handle(event);
            tally(outcome);
        } catch (RuntimeException failure) {
            failures.incrementAndGet();
            AppLogger.error(NetworkEventProcessor.class, "Consumer failed on alarm "
                    + event.getEventReference() + "; carrying on with the rest", failure);
        } finally {
            completed.incrementAndGet();
            synchronized (pendingLock) {
                pending--;
                pendingLock.notifyAll();
            }
        }
    }
```

### 28b. Notification sweep

**File:** `src/main/java/com/amdocs/telecom/scheduler/NotificationProcessor.java`
**Lines:** 157–177

```java
    public int dispatchNow() {
        try {
            List<Notification> waiting = notifications.findPendingDispatch(batchSize);
            sweeps.incrementAndGet();
            int count = 0;
            for (Notification message : waiting) {
                if (claim(message.getId())) {
                    deliver(message);
                    count++;
                } else {
                    skipped.incrementAndGet();
                }
            }
            delivered.addAndGet(count);
            return count;
        } catch (RuntimeException failure) {
            failures.incrementAndGet();
            AppLogger.error(NotificationProcessor.class,
                    "A notification sweep failed; the processor carries on", failure);
            return 0;
        }
    }
```

---

## 29. Top-Level Exception Handling in `main`

**File:** `src/main/java/com/amdocs/telecom/main/TSATMSApplication.java`
**Lines:** 129–189 (the dispatch on command-line flags is shortened here)

```java
    public static void main(String[] args) {
        int exitCode;
        try {
            if (hasFlag(args, HELP_FLAG)) {
                exitCode = printUsage();
            } else if (hasFlag(args, SETUP_DATABASE_FLAG)) {
                exitCode = buildDatabase();
            // ... one branch per command-line switch ...
            } else {
                exitCode = runApplication();
            }
        } catch (TSATMSException failure) {
            System.out.println();
            System.out.println("  Startup failed.");
            System.out.println("  " + failure.toDisplayString());
            AppLogger.error(TSATMSApplication.class, "Startup failed", failure);
            exitCode = 2;
        } catch (RuntimeException failure) {
            System.out.println();
            System.out.println("  Startup failed unexpectedly: " + failure);
            AppLogger.error(TSATMSApplication.class, "Unexpected startup failure", failure);
            exitCode = 3;
        } finally {
            AppLogger.shutdown();
        }
        System.exit(exitCode);
    }
```

---

# PART D — JDBC

## 30. Connection

### 30a. `DBConnection` singleton

**File:** `src/main/java/com/amdocs/telecom/util/DBConnection.java`
**Lines:** 61–67 (singleton) and 104–114 (`getConnection()`, shown in 26b)

```java
    private static final class Holder {
        private static final DBConnection INSTANCE = new DBConnection();
    }

    public static DBConnection getInstance() {
        return Holder.INSTANCE;
    }
```

### 30b. `ConnectionScope` — reuse the transaction's connection or open a new one

**File:** `src/main/java/com/amdocs/telecom/dao/ConnectionScope.java`
**Lines:** 38–44

```java
    public static ConnectionScope open() {
        Connection active = TRANSACTIONAL.get();
        if (active != null) {
            return new ConnectionScope(active, false);
        }
        return new ConnectionScope(DBConnection.getInstance().getConnection(), true);
    }
```

---

## 31. PreparedStatement

**File:** `src/main/java/com/amdocs/telecom/dao/impl/JdbcOperations.java`
**Lines:** 92–99

```java
    protected int executeUpdate(String sql, Object... parameters) {
        try (ConnectionScope scope = ConnectionScope.open();
             PreparedStatement statement = scope.connection().prepareStatement(sql)) {
            JdbcSupport.bind(statement, parameters);
            return statement.executeUpdate();
        } catch (SQLException cause) {
            throw failure(ErrorCode.DB_UPDATE_FAILED, "Update failed", sql, parameters, cause);
        }
    }
```

**Parameter binding — `src/main/java/com/amdocs/telecom/dao/impl/JdbcSupport.java`, lines 38–45:**

```java
    static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        if (parameters == null) {
            return;
        }
        for (int index = 0; index < parameters.length; index++) {
            bindOne(statement, index + 1, parameters[index]);
        }
    }
```

---

## 32. ResultSet + RowMapper

The `RowMapper<T>` interface is in section 4a; the query loop is in section 25a.

**Concrete mapper — `src/main/java/com/amdocs/telecom/dao/impl/TroubleTicketDAOImpl.java`, lines 67–78:**

```java
    static TroubleTicket mapRow(ResultSet resultSet) throws SQLException {
        TroubleTicket ticket = new TroubleTicket();
        ticket.setId(resultSet.getLong("ticket_id"));
        ticket.setTicketNumber(resultSet.getString("ticket_number"));
        ticket.setCustomerId(resultSet.getLong("customer_id"));
        ticket.setServiceId(resultSet.getLong("service_id"));
        ticket.setCategory(JdbcSupport.enumValue(resultSet, "category", IncidentCategory.class));
        ticket.setDescription(resultSet.getString("description"));
        ticket.setPriority(JdbcSupport.enumValue(resultSet, "priority", Priority.class));
        ticket.setSeverity(JdbcSupport.enumValue(resultSet, "severity", Severity.class));
        ticket.setStatus(JdbcSupport.enumValue(resultSet, "status", TicketStatus.class));
        ticket.setAssignedEngineerId(JdbcSupport.nullableLong(resultSet, "assigned_engineer_id"));
```

---

## 33. CallableStatement (stored procedures)

### 33a. Procedure with OUT parameters

**File:** `src/main/java/com/amdocs/telecom/dao/impl/ReportDAOImpl.java`
**Lines:** 358–372

```java
    public ProcedureOutcome assignEngineerViaProcedure(Long ticketId, Long engineerId, String actor) {
        final String call = "{CALL sp_assign_engineer(?, ?, ?, ?, ?)}";
        try (ConnectionScope scope = ConnectionScope.open();
             CallableStatement statement = scope.connection().prepareCall(call)) {
            statement.setLong(1, ticketId);
            statement.setLong(2, engineerId);
            statement.setString(3, actor);
            statement.registerOutParameter(4, Types.VARCHAR);
            statement.registerOutParameter(5, Types.VARCHAR);

            statement.execute();
            return new ProcedureOutcome(statement.getString(4), statement.getString(5));
        } catch (SQLException cause) {
            throw failure(ErrorCode.DB_TRANSACTION_FAILED, "Procedure call failed", call, null, cause);
        }
    }
```

### 33b. Procedure returning a result set

**File:** `src/main/java/com/amdocs/telecom/dao/impl/ReportDAOImpl.java`
**Lines:** 296–318

```java
    public List<EngineerRecommendationDTO> recommendEngineers(Specialization specialization,
                                                              Region region, int limit) {
        final String call = "{CALL sp_recommend_engineers(?, ?, ?)}";
        try (ConnectionScope scope = ConnectionScope.open();
             CallableStatement statement = scope.connection().prepareCall(call)) {
            statement.setString(1, specialization == null ? null : specialization.name());
            if (region == null) {
                statement.setNull(2, Types.VARCHAR);
            } else {
                statement.setString(2, region.name());
            }
            statement.setInt(3, limit);

            try (ResultSet resultSet = statement.executeQuery()) {
                List<EngineerRecommendationDTO> recommendations = new ArrayList<>();
                while (resultSet.next()) {
                    recommendations.add(mapRecommendation(resultSet));
                }
                return recommendations;
            }
        } catch (SQLException cause) {
            throw failure(ErrorCode.DB_QUERY_FAILED, "Procedure call failed", call, null, cause);
        }
    }
```

Also: `ticketVolumeReport(...)` at lines 327–346 calls `sp_ticket_volume_report`.

---

## 34. Transactions (`setAutoCommit(false)`, `commit`, `rollback`)

**File:** `src/main/java/com/amdocs/telecom/dao/TransactionTemplate.java`
**Lines:** 71–107 — full code in section 23a (`setAutoCommit(false)` line 77, `commit()` line 82, `rollbackQuietly` in each catch).

**Nested calls join the outer transaction — lines 54–58:**

```java
    public static <T> T execute(TransactionCallback<T> callback) {
        if (ConnectionScope.isTransactionActive()) {
            return callback.doInTransaction(new JdbcTransactionContext(ConnectionScope.activeConnection()));
        }
        return executeNew(callback);
    }
```

---

## 35. Savepoint

### 35a. Implementation

**File:** `src/main/java/com/amdocs/telecom/dao/TransactionTemplate.java`
**Lines:** 127–145

```java
        public Savepoint savepoint(String name) {
            try {
                return connection.setSavepoint(name);
            } catch (SQLException cause) {
                throw new DataAccessException(ErrorCode.DB_TRANSACTION_FAILED,
                        "Could not create savepoint '" + name + "': " + cause.getMessage(), cause);
            }
        }

        @Override
        public void rollbackTo(Savepoint savepoint) {
            try {
                connection.rollback(savepoint);
                AppLogger.warn(TransactionTemplate.class,
                        "Rolled back to savepoint " + describe(savepoint));
            } catch (SQLException cause) {
                throw new DataAccessException(ErrorCode.DB_ROLLBACK_FAILED,
                        "Could not roll back to savepoint: " + cause.getMessage(), cause);
            }
        }
```

The contract is declared in `src/main/java/com/amdocs/telecom/dao/TransactionContext.java`, lines 26–32.

### 35b. Usage — one savepoint per ticket in an assignment sweep

**File:** `src/main/java/com/amdocs/telecom/service/impl/EngineerAssignmentServiceImpl.java`
**Lines:** 255–270

```java
        return TransactionTemplate.execute(context -> {
            List<AssignmentResult> outcomes = new ArrayList<>(queue.size());
            for (TroubleTicket ticket : queue) {
                Savepoint marker = context.savepoint("sweep_" + ticket.getId());
                try {
                    outcomes.add(assignAutomatically(actor, ticket));
                    context.release(marker);
                } catch (BusinessException refused) {
                    context.rollbackTo(marker);
                    outcomes.add(AssignmentResult.skipped(ticket.getTicketNumber(),
                            refused.getMessage()));
                }
            }
```

---

## 36. Batch Processing

### 36a. `addBatch` + `executeBatch`

**File:** `src/main/java/com/amdocs/telecom/dao/impl/JdbcOperations.java`
**Lines:** 133–148

```java
    protected int[] executeBatch(String sql, List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return new int[0];
        }
        try (ConnectionScope scope = ConnectionScope.open();
             PreparedStatement statement = scope.connection().prepareStatement(sql)) {
            for (Object[] row : rows) {
                JdbcSupport.bind(statement, row);
                statement.addBatch();
            }
            int[] results = statement.executeBatch();
            AppLogger.debug(getClass(), "Batched " + results.length + " rows into " + sourceName());
            return results;
        } catch (SQLException cause) {
            throw failure(ErrorCode.DB_BATCH_FAILED, "Batch failed", sql, null, cause);
        }
    }
```

### 36b. Used by the notification DAO

**File:** `src/main/java/com/amdocs/telecom/dao/impl/NotificationDAOImpl.java`
**Lines:** 155–165

```java
    public int insertBatch(List<Notification> notifications) {
        if (notifications == null || notifications.isEmpty()) {
            return 0;
        }
        List<Object[]> rows = new ArrayList<>(notifications.size());
        for (Notification notification : notifications) {
            rows.add(insertParameters(notification));
        }
        return countBatchWrites(executeBatch(INSERT_SQL, rows));
    }
```

Batching is also used by `AuditLogDAOImpl` and `NetworkEventDAOImpl`.

---

## 37. Section 19 — Engineer Assignment in One Transaction

### 37a. Entry point

**File:** `src/main/java/com/amdocs/telecom/service/impl/EngineerAssignmentServiceImpl.java`
**Lines:** 152–157

```java
    public AssignmentResult assign(UserSession actor, String ticketNumber, String employeeCode) {
        TroubleTicket ticket = authorise(actor, ticketNumber);
        NetworkEngineer engineer = requireEngineer(employeeCode);
        return TransactionTemplate.execute(context ->
                handOver(actor, ticket, engineer, null, null));
    }
```

### 37b. The steps inside the transaction (`handOver`)

**File:** `src/main/java/com/amdocs/telecom/service/impl/EngineerAssignmentServiceImpl.java`
**Lines:** 298–355 (comments shortened)

```java
    private AssignmentResult handOver(UserSession actor, TroubleTicket ticket,
                                      NetworkEngineer engineer, MatchTier tier, String remark) {
        // Step 1: validate the ticket.
        if (ticket.getAssignedEngineerId() != null) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "Ticket " + ticket.getTicketNumber() + " is already with engineer "
                            + codeOf(ticket.getAssignedEngineerId())
                            + ". Use reassignment to move it.");
        }
        TicketStatus from = ticket.getStatus();
        TicketLifecycle.requireTransition(ticket.getTicketNumber(), from, TicketStatus.ASSIGNED);

        // Step 2 and 3: validate the engineer and check their availability.
        requireCanTakeWork(engineer);

        // Step 4: assign the engineer (claim one of their slots).
        if (!engineers.incrementWorkload(engineer.getId())) {
            throw new BusinessException(ErrorCode.ENGINEER_UNAVAILABLE,
                    "Engineer " + engineer.getEmployeeCode() + " reached capacity before the "
                            + "ticket could be handed over.");
        }

        // Step 5: update the ticket.
        if (!tickets.assignEngineer(ticket.getId(), engineer.getId(), from)) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, /* ... */);
        }
        engineers.refreshAvailability(engineer.getId());

        // Step 6: the status history entry.
        history.insert(new TicketStatusHistory(ticket.getId(), from, TicketStatus.ASSIGNED,
                actor.getUsername(), Validators.shorten(describeHandover(engineer, tier, remark),
                TicketValidator.REMARKS_MAX)));

        // Steps 7 and 8: the notification and the audit record.
        events.publish(TicketEvent.assigned(reload(ticket), actor, null,
                engineer.getEmployeeCode(), tier == null ? remark : tier.getReason()));

        // Step 9 is the commit, done by TransactionTemplate.
        return AssignmentResult.assigned(ticket.getTicketNumber(), engineer.getEmployeeCode(),
                tier);
    }
```

---

# PART E — CORE JAVA / OOP

## 38. Encapsulation

**File:** `src/main/java/com/amdocs/telecom/model/TroubleTicket.java`
**Lines:** 28–80 (extract)

```java
    private String ticketNumber;
    private Long customerId;
    private Long serviceId;
    private IncidentCategory category;
    private String description;
    private Priority priority;
    private Severity severity;
    private TicketStatus status = TicketStatus.OPEN;
    private Long assignedEngineerId;
    private EscalationLevel escalationLevel = EscalationLevel.ENGINEER;
    private SLAStatus slaStatus = SLAStatus.WITHIN_SLA;

    public String getTicketNumber() {
        return ticketNumber;
    }

    public void setTicketNumber(String ticketNumber) {
        this.ticketNumber = ticketNumber;
    }
```

## 39. Inheritance

**`BaseEntity` → `TimestampedEntity` → `TroubleTicket`** — `src/main/java/com/amdocs/telecom/model/TroubleTicket.java`, line 17:

```java
public class TroubleTicket extends TimestampedEntity implements Auditable {
```

**`AbstractParty` → `Customer`** — `src/main/java/com/amdocs/telecom/model/Customer.java`, lines 12 and 119–127:

```java
public class Customer extends AbstractParty {
    // ...
    @Override
    public Role getRole() {
        return Role.CUSTOMER;
    }

    @Override
    public String getBusinessKey() {
        return customerNumber;
    }
```

`NetworkEngineer` also extends `AbstractParty`.

## 40. Abstract Classes

**`src/main/java/com/amdocs/telecom/model/AbstractParty.java`, lines 14–56 (extract):**

```java
public abstract class AbstractParty extends TimestampedEntity implements Auditable {

    private String name;
    private String email;

    public abstract String getBusinessKey();

    public abstract Role getRole();
```

**`src/main/java/com/amdocs/telecom/dao/impl/AbstractJdbcDAO.java`, lines 23–47 (extract):**

```java
public abstract class AbstractJdbcDAO<T extends BaseEntity> extends JdbcOperations
        implements GenericDAO<T, Long> {

    @Override
    public abstract String tableName();

    protected abstract String idColumn();

    protected abstract RowMapper<T> mapper();

    protected abstract String insertSql();
```

**`src/main/java/com/amdocs/telecom/controller/Dashboard.java`, lines 36–66** — see section 51 (Template Method).

## 41. Interfaces

| Interface | File | Lines |
|---|---|---|
| `Identifiable<ID>` | `src/main/java/com/amdocs/telecom/model/Identifiable.java` | 12–22 |
| `Displayable` | `src/main/java/com/amdocs/telecom/model/Displayable.java` | 17–47 |
| `Auditable` | `src/main/java/com/amdocs/telecom/model/Auditable.java` | 10–21 |
| `DescribableEnum` | `src/main/java/com/amdocs/telecom/model/enums/DescribableEnum.java` | 18–52 |

```java
public interface Identifiable<ID> {

    ID getId();

    default boolean isPersisted() {
        return getId() != null;
    }
}
```

## 42. Polymorphism

### 42a. `SlaClock` chosen at runtime

**File:** `src/main/java/com/amdocs/telecom/service/impl/SlaServiceImpl.java`
**Lines:** 190–195

```java
        SLAConfiguration configuration = configurationFor(ticket.getPriority());
        SlaClock clock = clocks.clockFor(ticket.getPriority());
        ticket.setSlaResponseDeadline(
                clock.deadlineFrom(raisedAt, configuration.getResponseMinutes()));
        ticket.setSlaDeadline(
                clock.deadlineFrom(raisedAt, configuration.getResolutionMinutes()));
```

### 42b. `ReportWriter` chosen at runtime (CSV or text)

**File:** `src/main/java/com/amdocs/telecom/report/ReportExporter.java`
**Lines:** 77–85

```java
        Path file = directory.resolve(fileNameFor(table, format));
        ReportWriter writer = format.newWriter();
        try {
            Files.createDirectories(directory);
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write(table, out);
            }
```

## 43. Generics

**`src/main/java/com/amdocs/telecom/dao/GenericDAO.java`, lines 25–34:**

```java
public interface GenericDAO<T extends Identifiable<ID>, ID> {

    Optional<T> findById(ID id);

    List<T> findAll();

    T insert(T entity);

    boolean update(T entity);
```

**`src/main/java/com/amdocs/telecom/service/analytics/Tally.java`, lines 19–42 (extract):**

```java
public final class Tally<T> implements Displayable {

    private final T key;
    private final String label;
    private final long count;
    private final double share;

    public static <T> Tally<T> of(T key, String label, long count, long total) {
        double share = total <= 0 ? 0.0d
                : Math.round(10000.0d * count / total) / 100.0d;
        return new Tally<T>(key, label, count, share);
    }
```

`RowMapper<T>` is in section 4a.

## 44. Enums With Behaviour

**`src/main/java/com/amdocs/telecom/model/enums/Priority.java`, lines 12–49 (extract):**

```java
public enum Priority implements DescribableEnum {

    LOW("P4", "Low", 1),
    MEDIUM("P3", "Medium", 2),
    HIGH("P2", "High", 3),
    CRITICAL("P1", "Critical", 4);
    // ...
    public static Comparator<Priority> mostUrgentFirst() {
        return Comparator.comparingInt(Priority::getWeight).reversed();
    }
```

**`src/main/java/com/amdocs/telecom/model/enums/TicketStatus.java`, lines 41–66 (extract):**

```java
    public boolean isTerminal() {
        return this == CLOSED || this == CANCELLED;
    }

    public boolean isActive() {
        return !isTerminal() && this != RESOLVED;
    }
```

**`src/main/java/com/amdocs/telecom/model/enums/EscalationLevel.java`, lines 43–51:**

```java
    public Optional<EscalationLevel> next() {
        EscalationLevel[] levels = values();
        int nextIndex = ordinal() + 1;
        return nextIndex < levels.length ? Optional.of(levels[nextIndex]) : Optional.<EscalationLevel>empty();
    }

    public boolean isTopLevel() {
        return this == OPERATIONS_MANAGER;
    }
```

**`src/main/java/com/amdocs/telecom/model/enums/Role.java`, lines 35–44:**

```java
    public boolean isStaff() {
        return this != CUSTOMER;
    }

    public boolean canViewReports() {
        return this == SERVICE_DESK || this == NETWORK_MANAGER;
    }
```

## 45. `equals` / `hashCode` / `toString`

**File:** `src/main/java/com/amdocs/telecom/model/BaseEntity.java`
**Lines:** 43–63

```java
    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        BaseEntity that = (BaseEntity) other;
        return id != null && id.equals(that.id);
    }

    @Override
    public final int hashCode() {
        return id == null ? System.identityHashCode(this) : id.hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" + toSummaryLine() + "}";
    }
```

## 46. Collections

| Collection | File | Lines |
|---|---|---|
| `List` | `src/main/java/com/amdocs/telecom/dao/impl/AbstractJdbcDAO.java` | 72–74 |
| `EnumMap` + `EnumSet` | `src/main/java/com/amdocs/telecom/validation/TicketLifecycle.java` | 53–58 |
| `EnumSet` | `src/main/java/com/amdocs/telecom/security/Permission.java` | 126–130 |
| `PriorityQueue` / `Queue` | `src/main/java/com/amdocs/telecom/service/impl/EscalationServiceImpl.java` | 152–163 (section 20) |
| `BlockingQueue` | `src/main/java/com/amdocs/telecom/scheduler/NetworkEventProcessor.java` | 117–119 (section 13) |
| `ConcurrentHashMap` | `src/main/java/com/amdocs/telecom/scheduler/SlaMonitor.java` | 73 (section 16g) |

```java
    private static Map<TicketStatus, Set<TicketStatus>> buildGraph() {
        Map<TicketStatus, Set<TicketStatus>> graph =
                new EnumMap<TicketStatus, Set<TicketStatus>>(TicketStatus.class);

        graph.put(TicketStatus.OPEN,
                EnumSet.of(TicketStatus.ASSIGNED, TicketStatus.CANCELLED));
```

---

# PART F — DESIGN PATTERNS

## 47. Singleton

**`src/main/java/com/amdocs/telecom/util/ConfigLoader.java`, lines 54–61:**

```java
    private static final class Holder {
        private static final ConfigLoader INSTANCE = new ConfigLoader();
    }

    public static ConfigLoader getInstance() {
        return Holder.INSTANCE;
    }
```

The same holder pattern is used in `src/main/java/com/amdocs/telecom/util/DBConnection.java` (lines 61–67) and `src/main/java/com/amdocs/telecom/dao/DAOFactory.java` (lines 30–39).

## 48. Factory

**`src/main/java/com/amdocs/telecom/report/ReportFormat.java`, lines 52–60:**

```java
    public ReportWriter newWriter() {
        switch (this) {
            case CSV:
                return new CsvReportWriter();
            case TEXT:
                return new TextReportWriter();
            default:
                throw new IllegalStateException("No writer is wired for " + this);
        }
    }
```

**`src/main/java/com/amdocs/telecom/dao/DAOFactory.java`, lines 45–51:**

```java
    public static DAOFactory forVendor(Vendor vendor) {
        if (vendor == Vendor.MYSQL) {
            return new MySQLDAOFactory();
        }
        throw new ConfigurationException(ErrorCode.CONFIG_INVALID_VALUE,
                "No DAO factory is registered for database '" + vendor + "'");
    }
```

## 49. Strategy

**`src/main/java/com/amdocs/telecom/service/sla/SlaClockRegistry.java`, lines 97–104:**

```java
    private static SlaClock create(String name, ConfigLoader config) {
        String requested = name == null ? "" : name.trim().toLowerCase(Locale.ENGLISH);
        if (ContinuousSlaClock.NAME.equals(requested)) {
            return ContinuousSlaClock.getInstance();
        }
        if (BusinessHoursSlaClock.NAME.equals(requested)) {
            return businessHoursFrom(config);
        }
```

**`src/main/java/com/amdocs/telecom/report/ReportWriter.java`, lines 21–36:**

```java
public interface ReportWriter {

    String getExtension();

    ReportFormat getFormat();

    void write(ReportTable table, Appendable destination) throws IOException;
```

Implementations: `report/CsvReportWriter.java`, `report/TextReportWriter.java`.

## 50. Observer

**Subject — `src/main/java/com/amdocs/telecom/service/event/TicketEventPublisher.java`, lines 71–76 and 115–126:**

```java
    private static TicketEventPublisher standard() {
        TicketEventPublisher publisher = new TicketEventPublisher();
        publisher.register(new AuditTrailListener());
        publisher.register(new NotificationServiceImpl());
        return publisher;
    }

    public int publish(TicketEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("An event is required");
        }
        int told = 0;
        for (TicketEventListener listener : listeners) {
            if (!listener.isInterestedIn(event.getType())) {
                continue;
            }
            listener.onTicketEvent(event);
```

**Observer 1 — `src/main/java/com/amdocs/telecom/service/event/AuditTrailListener.java`, lines 44–50:**

```java
    @Override
    public void onTicketEvent(TicketEvent event) {
        auditLog.insert(AuditLog
                .forEntity(event.getTicket(), event.getType().getAuditAction(), event.getActor())
                .withChange(fit(event.findFromValue().orElse(null)),
                        fit(event.findToValue().orElse(null)))
                .withDetails(fit(event.findNote().orElse(null))));
    }
```

**Observer 2 — `src/main/java/com/amdocs/telecom/service/impl/NotificationServiceImpl.java`, lines 128–136:**

```java
    @Override
    public boolean isInterestedIn(TicketEventType type) {
        return type.notifies();
    }

    @Override
    public void onTicketEvent(TicketEvent event) {
        publish(event);
    }
```

## 51. Template Method

**File:** `src/main/java/com/amdocs/telecom/controller/Dashboard.java`
**Lines:** 36–66 (extract)

```java
public abstract class Dashboard {

    protected final UserSession session;
    protected final ConsoleReader console;

    public abstract String getTitle();

    protected abstract Menu buildMenu();

    public final void open() {
        AppLogger.info(getClass(), session.getUsername() + " opened " + getTitle());
        printBanner();
        buildMenu().runUntilExit(console, session);
        AppLogger.info(getClass(), session.getUsername() + " left " + getTitle());
    }
```

The four dashboards (`CustomerDashboard`, `ServiceDeskDashboard`, `NetworkEngineerDashboard`, `NetworkManagerDashboard`) only supply `getTitle()` and `buildMenu()`.
`BackgroundServices.startAll()` / `stopAll()` (section 16a and 18b) drive the same fixed lifecycle over every `BackgroundWorker`.

## 52. DAO Pattern

**Interface — `src/main/java/com/amdocs/telecom/dao/TroubleTicketDAO.java`, lines 20–28:**

```java
public interface TroubleTicketDAO extends GenericDAO<TroubleTicket, Long> {

    Optional<TroubleTicket> findByTicketNumber(String ticketNumber);

    List<TroubleTicket> findByCustomerId(Long customerId);

    List<TroubleTicket> findByEngineerId(Long engineerId);

    List<TroubleTicket> findByStatus(TicketStatus status);
```

**Implementation — `src/main/java/com/amdocs/telecom/dao/impl/TroubleTicketDAOImpl.java`, line 29:**

```java
public class TroubleTicketDAOImpl extends AbstractJdbcDAO<TroubleTicket>
        implements TroubleTicketDAO {
```

---

# PART G — FILE HANDLING

## 53. Reading and Writing Files

### 53a. Reading configuration (`Properties.load`)

**File:** `src/main/java/com/amdocs/telecom/util/ConfigLoader.java`
**Lines:** 37–48 and 63–70 (the second part is in section 26c)

```java
    private ConfigLoader() {
        Properties loaded = new Properties();
        String source;

        File external = new File(CONFIG_FILE_NAME);
        if (external.isFile()) {
            source = external.getAbsolutePath();
            readFrom(loaded, external);
        } else {
            source = "classpath:" + CONFIG_FILE_NAME;
            readFromClasspath(loaded);
        }
```

### 53b. Reading SQL scripts (`BufferedReader`)

**File:** `src/main/java/com/amdocs/telecom/util/SqlScriptRunner.java`
**Lines:** 74–88

```java
    private static String readResource(String resourcePath) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new DataAccessException(ErrorCode.DB_QUERY_FAILED,
                        "SQL script '" + resourcePath + "' was not found on the classpath");
            }
            StringBuilder builder = new StringBuilder(8192);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append('\n');
                }
            }
```

### 53c. Writing reports (`Files.newBufferedWriter`)

**File:** `src/main/java/com/amdocs/telecom/report/ReportExporter.java`, lines 79–89 — see section 25b. Output goes to the `reports/` folder.

**CSV writer — `src/main/java/com/amdocs/telecom/report/CsvReportWriter.java`, lines 65–72:**

```java
    public void write(ReportTable table, Appendable destination) throws IOException {
        if (table == null || destination == null) {
            throw new IllegalArgumentException("A report and a destination are required");
        }
        writeRow(table.getHeaders(), destination);
        for (List<String> row : table.getRows()) {
            writeRow(row, destination);
        }
    }
```

### 53d. Writing logs (`FileHandler`)

**File:** `src/main/java/com/amdocs/telecom/util/AppLogger.java`
**Lines:** 75–93

```java
    private static void attachFileHandler(ConfigLoader config, Level fileLevel) {
        String directory = config.getString("log.directory", "logs");
        String fileName = config.getString("log.file", "tsatms.log");
        int sizeLimit = config.getInt("log.file.size.bytes", 5 * 1024 * 1024);
        int fileCount = Math.max(1, config.getInt("log.file.count", 5));

        File logDirectory = new File(directory);
        if (!logDirectory.isDirectory() && !logDirectory.mkdirs()) {
            initialisationWarning = "Could not create log directory " + logDirectory.getAbsolutePath()
                    + "; file logging is disabled for this run.";
            return;
        }

        String pattern = new File(logDirectory, fileName).getPath();
        try {
            FileHandler fileHandler = new FileHandler(pattern, sizeLimit, fileCount, true);
            fileHandler.setLevel(fileLevel);
            fileHandler.setFormatter(new SingleLineFormatter());
            LOGGER.addHandler(fileHandler);
```

---

# PART H — SECURITY AND VALIDATION

## 54. Security

### 54a. CAPTCHA

**File:** `src/main/java/com/amdocs/telecom/security/CaptchaGenerator.java`
**Lines:** 31–40

```java
    public static CaptchaChallenge next() {
        return next(AppConstants.CAPTCHA_LENGTH);
    }

    public static CaptchaChallenge next(int length) {
        char[] characters = new char[Math.max(1, length)];
        for (int index = 0; index < characters.length; index++) {
            characters[index] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new CaptchaChallenge(new String(characters), AppConstants.CAPTCHA_VALIDITY_SECONDS);
    }
```

### 54b. Password hashing (PBKDF2 with a per-account salt)

**File:** `src/main/java/com/amdocs/telecom/security/PasswordHasher.java`
**Lines:** 68–78

```java
    public static HashedPassword hash(char[] password) {
        if (password == null || password.length == 0) {
            throw new TSATMSException(ErrorCode.VALIDATION_REQUIRED_FIELD,
                    "A password is required before it can be hashed");
        }
        byte[] salt = new byte[AppConstants.PASSWORD_SALT_BYTES];
        RANDOM.nextBytes(salt);

        String saltHex = toHex(salt);
        String hash = derive(password, salt, ACTIVE_ALGORITHM);
        return new HashedPassword(tagOf(ACTIVE_ALGORITHM) + TAG_SEPARATOR + hash, saltHex);
    }
```

### 54c. OTP (second factor)

**File:** `src/main/java/com/amdocs/telecom/service/impl/AuthenticationServiceImpl.java`
**Lines:** 196–200 and 223–228

```java
    private LoginResult issueOtpFor(UserAccount account) {
        String destination = maskedDestinationFor(account);
        String delivery = otpService.issueAndRenderDelivery(account.getUsername(), destination);
        return LoginResult.otpRequired(delivery);
    }
```

```java
        OtpService.OtpResult result = otpService.verify(enteredName, otpCode);
        if (!result.isAccepted()) {
            LoginOutcome outcome = outcomeFor(result);
            record(account, outcome);
            return LoginResult.failure(outcome, messageFor(result));
        }
```

### 54d. Account lock after repeated failures

**`src/main/java/com/amdocs/telecom/model/UserAccount.java`, lines 122–127:**

```java
    public boolean isCurrentlyLocked() {
        if (accountStatus == AccountStatus.LOCKED) {
            return lockedUntil == null || lockedUntil.isAfter(LocalDateTime.now());
        }
        return false;
    }
```

**`src/main/java/com/amdocs/telecom/service/impl/AuthenticationServiceImpl.java`, lines 173–185 (extract):**

```java
    private LoginResult handleWrongPassword(UserAccount account) {
        Optional<UserAccount> updated = users.registerFailedAttempt(account.getId(),
                AppConstants.MAX_FAILED_LOGIN_ATTEMPTS, AppConstants.ACCOUNT_LOCK_MINUTES);

        UserAccount current = updated.orElse(account);
        if (current.isCurrentlyLocked()) {
            record(current, LoginOutcome.ACCOUNT_LOCKED);
            return LoginResult.lockedOut(current.getLockedUntil());
        }
```

### 54e. Role-based access control

**File:** `src/main/java/com/amdocs/telecom/security/AccessControl.java`, lines 56–74 — see section 27c.
Each permission's allowed roles are declared in `src/main/java/com/amdocs/telecom/security/Permission.java` (lines 126–130).

---

## 55. Validation and the Ticket State Machine

### 55a. Field validators

**File:** `src/main/java/com/amdocs/telecom/validation/Validators.java`
**Lines:** 25–38

```java
    public static void requireText(ValidationResult result, String label, String value,
                                   int minLength, int maxLength) {
        if (isBlank(value)) {
            result.reject(label + " is required");
            return;
        }
        String trimmed = value.trim();
        if (trimmed.length() < minLength) {
            result.reject(label + " must be at least " + minLength + " characters");
        }
        if (trimmed.length() > maxLength) {
            result.reject(label + " must be " + maxLength + " characters or fewer, but is "
                    + trimmed.length());
        }
    }
```

### 55b. Ticket validation

**File:** `src/main/java/com/amdocs/telecom/validation/TicketValidator.java`
**Lines:** 53–65

```java
    public static void validateRaise(TicketRequest request) {
        if (request == null) {
            ValidationResult.forOperation("The ticket could not be raised")
                    .reject("No ticket details were supplied")
                    .throwIfInvalid();
            return;
        }
        ValidationResult result = ValidationResult.forOperation("The ticket could not be raised");
        Validators.requireIdentifier(result, "Service", request.getServiceId());
        Validators.requireValue(result, "Incident category", request.getCategory());
        Validators.requireText(result, "Description", request.getDescription(),
                DESCRIPTION_MIN, DESCRIPTION_MAX);
        result.throwIfInvalid();
    }
```

### 55c. State machine (legal status moves)

**File:** `src/main/java/com/amdocs/telecom/validation/TicketLifecycle.java`
**Lines:** 53–58 (the graph, section 46) and 95–113

```java
    public static boolean canMove(TicketStatus from, TicketStatus to) {
        return from != null && to != null && nextStatesFrom(from).contains(to);
    }

    public static void requireTransition(String ticketNumber, TicketStatus from,
                                         TicketStatus to) {
        if (canMove(from, to)) {
            return;
        }
        throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                describeRefusal(ticketNumber, from, to));
    }
```

---

## How to See Each Concept Running

| Concept area | Command |
|---|---|
| Streams and the nine analyses | `java -jar target/tsatms-jar-with-dependencies.jar --verify-reports` |
| Engineer recommendation (Stream + Comparator + Optional) | `--verify-assign` |
| Multithreading and synchronization | `--verify-threads` |
| Exceptions and the ticket state machine | `--verify-ticket` |
| JDBC data access | `--verify-dao` |
| Security | `--verify-security` |
| Everything end to end | `--demo` |
