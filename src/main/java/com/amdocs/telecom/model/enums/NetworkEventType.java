package com.amdocs.telecom.model.enums;

/**
 * Types of alarm raised by network elements, feeding the event processor
 * described in section 11 of the case study.
 *
 * <p>Events flagged as ticket worthy cause the background consumer to open a
 * trouble ticket automatically; the rest are logged only.</p>
 */
public enum NetworkEventType implements DescribableEnum {

    LINK_DOWN("EV01", "Link Down", true, IncidentCategory.NETWORK_OUTAGE),
    LINK_UP("EV02", "Link Up", false, IncidentCategory.OTHER),
    NODE_UNREACHABLE("EV03", "Node Unreachable", true, IncidentCategory.NO_CONNECTIVITY),
    HIGH_LATENCY("EV04", "High Latency", true, IncidentCategory.SLOW_DATA),
    PACKET_LOSS("EV05", "Packet Loss", true, IncidentCategory.SLOW_DATA),
    POWER_OUTAGE("EV06", "Power Outage", true, IncidentCategory.NETWORK_OUTAGE),
    CONGESTION("EV07", "Congestion", true, IncidentCategory.SLOW_DATA),
    HARDWARE_ALARM("EV08", "Hardware Alarm", true, IncidentCategory.ENTERPRISE_LINK),
    CONFIG_CHANGE("EV09", "Configuration Change", false, IncidentCategory.OTHER),
    HEARTBEAT("EV10", "Heartbeat", false, IncidentCategory.OTHER);

    private final String code;
    private final String displayName;
    private final boolean ticketWorthy;
    private final IncidentCategory mappedCategory;

    NetworkEventType(String code, String displayName, boolean ticketWorthy,
                     IncidentCategory mappedCategory) {
        this.code = code;
        this.displayName = displayName;
        this.ticketWorthy = ticketWorthy;
        this.mappedCategory = mappedCategory;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Whether an event of this type should raise a trouble ticket on its own.
     */
    public boolean isTicketWorthy() {
        return ticketWorthy;
    }

    /**
     * Incident category applied to an automatically raised ticket.
     */
    public IncidentCategory getMappedCategory() {
        return mappedCategory;
    }
}
