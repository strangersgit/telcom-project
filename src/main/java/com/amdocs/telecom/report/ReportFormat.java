package com.amdocs.telecom.report;

import com.amdocs.telecom.model.enums.DescribableEnum;

/**
 * The formats a report can be exported in.
 *
 * <p>Also the factory that picks the writer. Putting
 * {@link #newWriter()} here rather than in a separate factory class means
 * the set of formats and the set of writers cannot drift apart: adding a
 * constant without wiring it up will not compile.</p>
 */
public enum ReportFormat implements DescribableEnum {

    /** Comma separated, for a spreadsheet. */
    CSV("FMT1", "CSV", "csv"),

    /** Fixed width columns, for reading and printing. */
    TEXT("FMT2", "Text", "txt");

    private final String code;
    private final String displayName;
    private final String extension;

    ReportFormat(String code, String displayName, String extension) {
        this.code = code;
        this.displayName = displayName;
        this.extension = extension;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    public String getExtension() {
        return extension;
    }

    /**
     * A writer for this format.
     *
     * <p>New each time rather than a shared instance. The writers hold
     * configuration read at construction, and a fresh one picks up a
     * changed delimiter without a restart.</p>
     */
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

    /**
     * The format matching a file extension, defaulting to text.
     */
    public static ReportFormat fromExtension(String extension) {
        if (extension != null) {
            String wanted = extension.trim().toLowerCase();
            for (ReportFormat format : values()) {
                if (format.extension.equals(wanted)) {
                    return format;
                }
            }
        }
        return TEXT;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
