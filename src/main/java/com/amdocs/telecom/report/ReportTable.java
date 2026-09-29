package com.amdocs.telecom.report;

import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.enums.DescribableEnum;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A finished report: a heading, some columns, some rows, and whatever
 * needs saying underneath.
 *
 * <h3>Why the cells are strings</h3>
 *
 * <p>A report is the end of the line. Nothing downstream sorts it or does
 * arithmetic on it; it gets written to a file or printed. Keeping typed
 * values would mean every writer had to know how to render a
 * {@code LocalDateTime}, a {@code Double} and eight kinds of enum, and
 * they would each do it slightly differently. Formatting once, where the
 * report is built and the types are known, means the CSV and the text
 * version cannot disagree about what a number looks like.</p>
 *
 * <p>The formatting itself is in {@link Builder#row(Object...)}, so a
 * report author writes {@code row(code, count, hours)} and gets consistent
 * output without thinking about it.</p>
 *
 * <h3>Immutable once built</h3>
 *
 * <p>Reports are produced on a thread pool and read by whoever asked for
 * them, so they cross a thread boundary. Everything is copied and wrapped
 * on the way out of the builder: there is no window in which a reader
 * could see a half filled report.</p>
 */
public final class ReportTable implements Displayable {

    private final ReportKind kind;
    private final String title;
    private final String scope;
    private final LocalDateTime generatedAt;
    private final List<ReportColumn> columns;
    private final List<List<String>> rows;
    private final List<String> notes;

    private ReportTable(Builder builder) {
        this.kind = builder.kind;
        this.title = builder.title;
        this.scope = builder.scope;
        this.generatedAt = builder.generatedAt;
        this.columns = Collections.unmodifiableList(
                new ArrayList<ReportColumn>(builder.columns));
        List<List<String>> copied = new ArrayList<List<String>>(builder.rows.size());
        for (List<String> row : builder.rows) {
            copied.add(Collections.unmodifiableList(new ArrayList<String>(row)));
        }
        this.rows = Collections.unmodifiableList(copied);
        this.notes = Collections.unmodifiableList(new ArrayList<String>(builder.notes));
    }

    public static Builder of(ReportKind kind) {
        return new Builder(kind);
    }

    public ReportKind getKind() {
        return kind;
    }

    public String getTitle() {
        return title;
    }

    /**
     * What the report covers, such as a date range, or empty when it
     * covers everything.
     */
    public String getScope() {
        return scope;
    }

    public LocalDateTime getGeneratedAt() {
        return generatedAt;
    }

    public List<ReportColumn> getColumns() {
        return columns;
    }

    public List<List<String>> getRows() {
        return rows;
    }

    /**
     * Totals, caveats and anything else that belongs under the table
     * rather than in it.
     */
    public List<String> getNotes() {
        return notes;
    }

    public int getRowCount() {
        return rows.size();
    }

    public int getColumnCount() {
        return columns.size();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public List<String> getHeaders() {
        List<String> headers = new ArrayList<String>(columns.size());
        for (ReportColumn column : columns) {
            headers.add(column.getHeader());
        }
        return headers;
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-32s %4d row(s)  generated %s", title, getRowCount(),
                Displayable.formatDateTime(generatedAt));
    }

    @Override
    public String toDetailBlock() {
        return ReportFormat.TEXT.newWriter().render(this);
    }

    @Override
    public String toString() {
        return toSummaryLine();
    }

    /**
     * Assembles a report a column and a row at a time.
     *
     * <p>Not a convenience: the alternative is a constructor taking two
     * nested lists and four other arguments, which no caller can read and
     * every caller gets wrong once. The builder also checks each row
     * against the declared columns as it is added, so a mismatch is
     * reported at the line that caused it rather than as a ragged file
     * somebody notices next week.</p>
     */
    public static final class Builder {

        private final ReportKind kind;
        private final List<ReportColumn> columns = new ArrayList<ReportColumn>();
        private final List<List<String>> rows = new ArrayList<List<String>>();
        private final List<String> notes = new ArrayList<String>();
        private String title;
        private String scope = "";
        private LocalDateTime generatedAt = LocalDateTime.now();

        private Builder(ReportKind kind) {
            if (kind == null) {
                throw new IllegalArgumentException("A report kind is required");
            }
            this.kind = kind;
            this.title = kind.getDisplayName();
        }

        public Builder title(String value) {
            this.title = value;
            return this;
        }

        public Builder scope(String value) {
            this.scope = value == null ? "" : value;
            return this;
        }

        public Builder generatedAt(LocalDateTime value) {
            this.generatedAt = value == null ? LocalDateTime.now() : value;
            return this;
        }

        /** A column of words. */
        public Builder text(String header) {
            columns.add(ReportColumn.text(header));
            return this;
        }

        /** A column of numbers, right aligned. */
        public Builder number(String header) {
            columns.add(ReportColumn.number(header));
            return this;
        }

        /**
         * Adds a row, formatting each value for its type.
         *
         * @throws IllegalStateException when the row does not match the
         *         declared columns, which is a mistake in the report rather
         *         than in the data
         */
        public Builder row(Object... values) {
            if (columns.isEmpty()) {
                throw new IllegalStateException(
                        "Declare the columns before adding rows to " + kind);
            }
            int given = values == null ? 0 : values.length;
            if (given != columns.size()) {
                throw new IllegalStateException(kind + " has " + columns.size()
                        + " column(s) but a row was given " + given + " value(s)");
            }
            List<String> row = new ArrayList<String>(columns.size());
            for (Object value : values) {
                row.add(format(value));
            }
            rows.add(row);
            return this;
        }

        /** A line under the table: a total, or something the reader should know. */
        public Builder note(String value) {
            if (value != null && !value.trim().isEmpty()) {
                notes.add(value.trim());
            }
            return this;
        }

        public ReportTable build() {
            if (columns.isEmpty()) {
                throw new IllegalStateException("A report needs at least one column: " + kind);
            }
            return new ReportTable(this);
        }

        /**
         * One place that decides what each kind of value looks like.
         *
         * <p>Doubles get two places because every figure in these reports
         * is either hours or a percentage, and both read badly at full
         * precision. Enums print their label rather than their constant
         * name, because a report is for a person.</p>
         */
        private static String format(Object value) {
            if (value == null) {
                return "-";
            }
            if (value instanceof Double || value instanceof Float) {
                return String.format("%.2f", ((Number) value).doubleValue());
            }
            if (value instanceof LocalDateTime) {
                return Displayable.formatDateTime((LocalDateTime) value);
            }
            if (value instanceof LocalDate) {
                return Displayable.formatDate((LocalDate) value);
            }
            if (value instanceof DescribableEnum) {
                return ((DescribableEnum) value).getDisplayName();
            }
            String text = String.valueOf(value);
            return text.trim().isEmpty() ? "-" : text;
        }
    }
}
