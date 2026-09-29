package com.amdocs.telecom.report;

import com.amdocs.telecom.model.Displayable;

import java.io.IOException;
import java.util.List;

/**
 * Writes a report as a fixed width table, for reading rather than for
 * loading.
 *
 * <h3>Why the widths are measured rather than fixed</h3>
 *
 * <p>Hard coded column widths are wrong twice: too narrow and an engineer
 * name is truncated, too wide and the table will not fit a console. So
 * each column is measured against its heading and every value in it, and
 * the table comes out exactly as wide as its contents need. The cost is
 * one pass over the rows before writing, which for a report already held
 * in memory is nothing.</p>
 *
 * <p>A ceiling still applies per column, because one pathological
 * description should not push every other column off the screen. Values
 * over the ceiling are cut with an ellipsis, which at least tells the
 * reader something was cut.</p>
 */
public final class TextReportWriter implements ReportWriter {

    /** The widest any single column may become. */
    private static final int MAX_COLUMN_WIDTH = 40;

    private static final String GAP = "  ";
    private static final String NEWLINE = System.lineSeparator();

    @Override
    public String getExtension() {
        return ReportFormat.TEXT.getExtension();
    }

    @Override
    public ReportFormat getFormat() {
        return ReportFormat.TEXT;
    }

    @Override
    public void write(ReportTable table, Appendable destination) throws IOException {
        if (table == null || destination == null) {
            throw new IllegalArgumentException("A report and a destination are required");
        }
        int[] widths = measure(table);
        int ruleWidth = ruleWidth(widths);

        destination.append(rule('=', ruleWidth)).append(NEWLINE);
        destination.append(table.getTitle()).append(NEWLINE);
        if (!table.getScope().isEmpty()) {
            destination.append(table.getScope()).append(NEWLINE);
        }
        destination.append("Generated ")
                .append(Displayable.formatDateTime(table.getGeneratedAt())).append(NEWLINE);
        destination.append(rule('=', ruleWidth)).append(NEWLINE);

        writeRow(table.getHeaders(), table, widths, destination);
        destination.append(rule('-', ruleWidth)).append(NEWLINE);

        if (table.isEmpty()) {
            destination.append("(nothing to report)").append(NEWLINE);
        } else {
            for (List<String> row : table.getRows()) {
                writeRow(row, table, widths, destination);
            }
        }

        destination.append(rule('-', ruleWidth)).append(NEWLINE);
        destination.append(String.valueOf(table.getRowCount())).append(" row(s)")
                .append(NEWLINE);
        for (String note : table.getNotes()) {
            destination.append(note).append(NEWLINE);
        }
    }

    private void writeRow(List<String> values, ReportTable table, int[] widths,
                          Appendable destination) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                line.append(GAP);
            }
            line.append(pad(clip(values.get(index), widths[index]), widths[index],
                    table.getColumns().get(index).isRightAligned()));
        }
        // Trailing spaces on a right hand text column serve nobody and make
        // diffs of two reports noisier than the difference between them.
        destination.append(trimEnd(line.toString())).append(NEWLINE);
    }

    /**
     * The width each column needs: its heading, or its widest value,
     * whichever is larger, up to the ceiling.
     */
    private int[] measure(ReportTable table) {
        List<String> headers = table.getHeaders();
        int[] widths = new int[headers.size()];
        for (int index = 0; index < headers.size(); index++) {
            widths[index] = Math.min(headers.get(index).length(), MAX_COLUMN_WIDTH);
        }
        for (List<String> row : table.getRows()) {
            for (int index = 0; index < row.size() && index < widths.length; index++) {
                String value = row.get(index);
                int length = value == null ? 1 : value.length();
                widths[index] = Math.max(widths[index], Math.min(length, MAX_COLUMN_WIDTH));
            }
        }
        return widths;
    }

    private int ruleWidth(int[] widths) {
        int total = 0;
        for (int width : widths) {
            total += width;
        }
        total += GAP.length() * Math.max(widths.length - 1, 0);
        return Math.max(total, 32);
    }

    private static String clip(String value, int width) {
        if (value == null) {
            return "-";
        }
        if (value.length() <= width) {
            return value;
        }
        return width <= 3 ? value.substring(0, width)
                : value.substring(0, width - 3) + "...";
    }

    private static String pad(String value, int width, boolean rightAligned) {
        int padding = width - value.length();
        if (padding <= 0) {
            return value;
        }
        StringBuilder spaces = new StringBuilder(padding);
        for (int index = 0; index < padding; index++) {
            spaces.append(' ');
        }
        return rightAligned ? spaces + value : value + spaces;
    }

    private static String rule(char character, int width) {
        StringBuilder rule = new StringBuilder(width);
        for (int index = 0; index < width; index++) {
            rule.append(character);
        }
        return rule.toString();
    }

    private static String trimEnd(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(0, end);
    }
}
