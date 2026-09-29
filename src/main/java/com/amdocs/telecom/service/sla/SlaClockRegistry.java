package com.amdocs.telecom.service.sla;

import com.amdocs.telecom.exception.ConfigurationException;
import com.amdocs.telecom.exception.ErrorCode;
import com.amdocs.telecom.model.enums.Priority;
import com.amdocs.telecom.util.ConfigLoader;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides which {@link SlaClock} each priority band is measured by.
 *
 * <p>Two of the case study's design patterns meet here. The registry itself
 * is a Singleton built with the same holder idiom as
 * {@link ConfigLoader}, so the assignment is read from configuration once.
 * {@link #create(String)} is a factory method turning a configured name into
 * the matching strategy, which keeps the {@code new} calls for the concrete
 * clocks in one place.</p>
 *
 * <p>Every band defaults to {@link ContinuousSlaClock}, which is what the
 * database assumes. Changing a band to {@code business-hours} makes the Java
 * engine authoritative for that band's at-risk percentage; see
 * {@link SlaClock#isContinuous()} for why.</p>
 */
public final class SlaClockRegistry {

    private static final String DEFAULT_CLOCK_KEY = "sla.clock.default";
    private static final String CLOCK_KEY_PREFIX = "sla.clock.";
    private static final String OPENS_KEY = "sla.business-hours.opens";
    private static final String CLOSES_KEY = "sla.business-hours.closes";
    private static final String DAYS_KEY = "sla.business-hours.days";

    private static final String FALLBACK_OPENS = "09:00";
    private static final String FALLBACK_CLOSES = "18:00";
    private static final String FALLBACK_DAYS = "MON,TUE,WED,THU,FRI";

    /** Shortest abbreviation accepted for a day, so TUE and THU stay distinct. */
    private static final int SHORTEST_DAY_ABBREVIATION = 3;

    private final Map<Priority, SlaClock> byPriority;

    private SlaClockRegistry(ConfigLoader config) {
        String defaultName = config.getString(DEFAULT_CLOCK_KEY, ContinuousSlaClock.NAME);

        Map<Priority, SlaClock> resolved = new EnumMap<Priority, SlaClock>(Priority.class);
        // Bands sharing a clock name share the instance, so two bands
        // configured the same way are measured by the very same object.
        Map<String, SlaClock> builtByName = new HashMap<String, SlaClock>();

        for (Priority priority : Priority.values()) {
            String configured = config.getString(
                    CLOCK_KEY_PREFIX + priority.name().toLowerCase(Locale.ENGLISH), defaultName);
            String key = configured.trim().toLowerCase(Locale.ENGLISH);
            SlaClock clock = builtByName.get(key);
            if (clock == null) {
                clock = create(configured, config);
                builtByName.put(key, clock);
            }
            resolved.put(priority, clock);
        }
        this.byPriority = Collections.unmodifiableMap(resolved);
    }

    /** Holder is not loaded until {@link #getInstance()} is first called. */
    private static final class Holder {
        private static final SlaClockRegistry INSTANCE =
                new SlaClockRegistry(ConfigLoader.getInstance());
    }

    public static SlaClockRegistry getInstance() {
        return Holder.INSTANCE;
    }

    /* ---------- Factory ---------- */

    /**
     * Turns a configured clock name into the strategy it names.
     *
     * @throws ConfigurationException when the name is not one this build
     *                                knows, rather than silently falling
     *                                back to a window the operator did not
     *                                ask for
     */
    public static SlaClock create(String name) {
        return create(name, ConfigLoader.getInstance());
    }

    private static SlaClock create(String name, ConfigLoader config) {
        String requested = name == null ? "" : name.trim().toLowerCase(Locale.ENGLISH);
        if (ContinuousSlaClock.NAME.equals(requested)) {
            return ContinuousSlaClock.getInstance();
        }
        if (BusinessHoursSlaClock.NAME.equals(requested)) {
            return businessHoursFrom(config);
        }
        throw new ConfigurationException(ErrorCode.CONFIG_INVALID_VALUE,
                "Unknown SLA clock '" + name + "'. Use '" + ContinuousSlaClock.NAME
                        + "' or '" + BusinessHoursSlaClock.NAME + "'.");
    }

    private static BusinessHoursSlaClock businessHoursFrom(ConfigLoader config) {
        LocalTime opens = parseTime(config.getString(OPENS_KEY, FALLBACK_OPENS), OPENS_KEY);
        LocalTime closes = parseTime(config.getString(CLOSES_KEY, FALLBACK_CLOSES), CLOSES_KEY);
        Set<DayOfWeek> days = parseDays(config.getString(DAYS_KEY, FALLBACK_DAYS));
        try {
            return new BusinessHoursSlaClock(opens, closes, days);
        } catch (IllegalArgumentException rejected) {
            // The clock refuses a nonsensical working week; reported here as
            // the configuration problem it actually is.
            throw new ConfigurationException(ErrorCode.CONFIG_INVALID_VALUE,
                    "The configured working hours are not usable: " + rejected.getMessage(),
                    rejected);
        }
    }

    private static LocalTime parseTime(String value, String key) {
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException cause) {
            throw new ConfigurationException(ErrorCode.CONFIG_INVALID_VALUE,
                    "Property '" + key + "' must be a 24 hour time such as 09:00 but was '"
                            + value + "'", cause);
        }
    }

    private static Set<DayOfWeek> parseDays(String value) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                days.add(parseDay(trimmed));
            }
        }
        if (days.isEmpty()) {
            throw new ConfigurationException(ErrorCode.CONFIG_INVALID_VALUE,
                    "Property '" + DAYS_KEY + "' named no working days");
        }
        return days;
    }

    private static DayOfWeek parseDay(String token) {
        String upper = token.toUpperCase(Locale.ENGLISH);
        if (upper.length() >= SHORTEST_DAY_ABBREVIATION) {
            for (DayOfWeek day : DayOfWeek.values()) {
                if (day.name().startsWith(upper)) {
                    return day;
                }
            }
        }
        throw new ConfigurationException(ErrorCode.CONFIG_INVALID_VALUE,
                "Property '" + DAYS_KEY + "' contains '" + token
                        + "', which is not a day. Use names such as MON or MONDAY.");
    }

    /* ---------- Lookups ---------- */

    /**
     * The clock measuring the given band. Never null, because the
     * constructor resolves every band up front.
     */
    public SlaClock clockFor(Priority priority) {
        if (priority == null) {
            throw new IllegalArgumentException("A priority is required to choose an SLA clock");
        }
        return byPriority.get(priority);
    }

    public Map<Priority, SlaClock> assignments() {
        return byPriority;
    }

    /**
     * True when every band counts every minute, which is the condition under
     * which the Java engine and the database must report identical figures.
     */
    public boolean isEverythingContinuous() {
        for (SlaClock clock : byPriority.values()) {
            if (!clock.isContinuous()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Lists the assignment for the console, most urgent band first.
     */
    public String describe() {
        StringBuilder builder = new StringBuilder();
        Priority[] bands = Priority.values();
        for (int index = bands.length - 1; index >= 0; index--) {
            Priority band = bands[index];
            builder.append(String.format("  %-10s %s", band.getDisplayName(),
                    clockFor(band).getDescription()));
            if (index > 0) {
                builder.append(System.lineSeparator());
            }
        }
        return builder.toString();
    }
}
