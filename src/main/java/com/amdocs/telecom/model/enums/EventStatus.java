package com.amdocs.telecom.model.enums;

/**
 * Processing state of a network event as it moves through the producer and
 * consumer pipeline.
 */
public enum EventStatus implements DescribableEnum {

    RECEIVED("ES1", "Received"),
    PROCESSING("ES2", "Processing"),
    TICKET_CREATED("ES3", "Ticket Created"),
    IGNORED("ES4", "Ignored"),
    FAILED("ES5", "Failed");

    private final String code;
    private final String displayName;

    EventStatus(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    public boolean isProcessed() {
        return this == TICKET_CREATED || this == IGNORED;
    }
}
