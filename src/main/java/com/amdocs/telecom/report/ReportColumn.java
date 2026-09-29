package com.amdocs.telecom.report;

/**
 * One column of a report: its heading and which way its values line up.
 *
 * <p>Alignment is carried with the column rather than decided by the
 * writer because only the code that built the report knows whether a
 * column holds counts or names. The CSV writer ignores it, which is
 * correct: alignment is a presentation concern and a CSV has no
 * presentation.</p>
 */
public final class ReportColumn {

    /** Which edge the values line up against when rendered as text. */
    public enum Alignment {
        LEFT,
        RIGHT
    }

    private final String header;
    private final Alignment alignment;

    private ReportColumn(String header, Alignment alignment) {
        if (header == null || header.trim().isEmpty()) {
            throw new IllegalArgumentException("A column needs a heading");
        }
        this.header = header.trim();
        this.alignment = alignment;
    }

    /** A column of words, lined up on the left. */
    public static ReportColumn text(String header) {
        return new ReportColumn(header, Alignment.LEFT);
    }

    /**
     * A column of numbers, lined up on the right so the digits stack and
     * a reader can compare magnitudes down the column.
     */
    public static ReportColumn number(String header) {
        return new ReportColumn(header, Alignment.RIGHT);
    }

    public String getHeader() {
        return header;
    }

    public Alignment getAlignment() {
        return alignment;
    }

    public boolean isRightAligned() {
        return alignment == Alignment.RIGHT;
    }

    @Override
    public String toString() {
        return header;
    }
}
