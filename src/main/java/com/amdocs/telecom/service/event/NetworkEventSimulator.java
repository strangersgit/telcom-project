package com.amdocs.telecom.service.event;

import com.amdocs.telecom.model.NetworkEvent;
import com.amdocs.telecom.model.enums.NetworkEventType;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.model.enums.Severity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manufactures the alarm stream section 11 asks the system to simulate.
 *
 * <p>Section 11 gives one worked example, and this follows its shape
 * exactly: reference {@code NE-884521}, node {@code MUM-RAN-045}, type
 * {@code LINK_DOWN}, severity {@code CRITICAL}.</p>
 *
 * <p>The mix is deliberately uneven. Most of what a network emits is not
 * worth a ticket, so the cycle produces heartbeats and link-up clears
 * alongside the faults; a simulator that only produced outages would make
 * the consumer's decision look trivial when deciding is most of its
 * work.</p>
 *
 * <p>Safe to share between threads. The only mutable state is the reference
 * counter, and it is an {@link AtomicInteger} because the producer and a
 * console operator can both ask for alarms at once. References are unique
 * within a run by construction and practically unique across runs because
 * the counter starts from the clock; {@code uq_events_reference} is the
 * backstop that makes "practically" good enough.</p>
 */
public final class NetworkEventSimulator {

    /** Node naming per region, so a node name says where it is. */
    private static final Map<Region, String> NODE_PREFIX = nodePrefixes();

    /**
     * The cycle of alarms, in the order they are handed out. Six of the ten
     * warrant a ticket, which is roughly what a quiet network looks like.
     */
    private static final List<NetworkEventType> CYCLE = Collections.unmodifiableList(
            new ArrayList<NetworkEventType>(java.util.Arrays.asList(
                    NetworkEventType.LINK_DOWN,
                    NetworkEventType.HEARTBEAT,
                    NetworkEventType.HIGH_LATENCY,
                    NetworkEventType.LINK_UP,
                    NetworkEventType.NODE_UNREACHABLE,
                    NetworkEventType.CONFIG_CHANGE,
                    NetworkEventType.PACKET_LOSS,
                    NetworkEventType.HEARTBEAT,
                    NetworkEventType.POWER_OUTAGE,
                    NetworkEventType.HARDWARE_ALARM)));

    private static final Region[] REGIONS = Region.values();

    private final AtomicInteger references;

    public NetworkEventSimulator() {
        // Six digits, matching NE-884521, starting somewhere the last run is
        // unlikely to have reached.
        this(100000 + (int) (System.nanoTime() % 800000L));
    }

    /**
     * @param firstReference the six digit number the first alarm carries,
     *                       fixed so a caller can predict the references
     */
    public NetworkEventSimulator(int firstReference) {
        this.references = new AtomicInteger(firstReference - 1);
    }

    /**
     * Builds alarms without storing them, so the caller decides whether they
     * go to the database, to a queue, or nowhere.
     */
    public List<NetworkEvent> next(int count) {
        List<NetworkEvent> batch = new ArrayList<NetworkEvent>(Math.max(count, 0));
        for (int index = 0; index < count; index++) {
            batch.add(next());
        }
        return batch;
    }

    public NetworkEvent next() {
        int sequence = references.incrementAndGet();
        NetworkEventType type = CYCLE.get(Math.abs(sequence) % CYCLE.size());
        Region region = REGIONS[Math.abs(sequence) % REGIONS.length];
        return build("NE-" + String.format("%06d", sequence % 1000000),
                nodeName(region, sequence), type, region);
    }

    /**
     * One alarm with everything chosen, for a caller that needs a particular
     * case rather than the next one in the cycle.
     */
    public static NetworkEvent build(String reference, String node, NetworkEventType type,
                                     Region region) {
        NetworkEvent event = new NetworkEvent(reference, node, type,
                severityFor(type), region, describe(type, node));
        return event;
    }

    /**
     * The severity a type of alarm normally carries.
     *
     * <p>Paired with the type rather than chosen at random, because the two
     * are not independent: a node that has stopped answering is never a
     * minor matter, and a heartbeat is never anything else. The consumer
     * needs both to agree before it raises a ticket, so a simulator that
     * scattered severities would exercise combinations no network
     * produces.</p>
     */
    private static Severity severityFor(NetworkEventType type) {
        switch (type) {
            case LINK_DOWN:
            case NODE_UNREACHABLE:
            case POWER_OUTAGE:
                return Severity.CRITICAL;
            case HARDWARE_ALARM:
                return Severity.MAJOR;
            case HIGH_LATENCY:
            case PACKET_LOSS:
            case CONGESTION:
                return Severity.MINOR;
            case LINK_UP:
            case CONFIG_CHANGE:
                return Severity.WARNING;
            default:
                return Severity.INFO;
        }
    }

    private static String describe(NetworkEventType type, String node) {
        return type.getDisplayName() + " reported by " + node;
    }

    private static String nodeName(Region region, int sequence) {
        return NODE_PREFIX.get(region) + "-RAN-" + String.format("%03d", Math.abs(sequence) % 1000);
    }

    private static Map<Region, String> nodePrefixes() {
        Map<Region, String> prefixes = new EnumMap<Region, String>(Region.class);
        prefixes.put(Region.NORTH, "DEL");
        prefixes.put(Region.SOUTH, "BLR");
        prefixes.put(Region.EAST, "KOL");
        prefixes.put(Region.WEST, "MUM");
        prefixes.put(Region.CENTRAL, "NAG");
        return Collections.unmodifiableMap(prefixes);
    }
}
