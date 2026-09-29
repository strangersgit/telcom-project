package com.amdocs.telecom.report;

import com.amdocs.telecom.util.ConfigLoader;

import java.io.IOException;
import java.util.List;

/**
 * Writes a report as comma separated values.
 *
 * <h3>Quoting, and why it is not optional</h3>
 *
 * <p>A ticket description can contain a comma, a quotation mark or a
 * newline, and any of the three will corrupt a naively written CSV: the
 * comma invents a column, the quote unbalances the field, the newline
 * invents a row. Every value is therefore quoted when it contains a
 * delimiter, a quote, a carriage return or a line feed, and internal
 * quotes are doubled, which is what RFC 4180 says and what every
 * spreadsheet expects.</p>
 *
 * <p>A value with leading or trailing spaces is quoted too. Unquoted,
 * they are silently eaten on import, and a ticket number that came back
 * different from the one that went out would be a hard afternoon.</p>
 *
 * <h3>What is not in the file</h3>
 *
 * <p>Only the header row and the data. The title, the generation time and
 * the notes are deliberately left out: this file exists to be loaded into
 * something else, and a spreadsheet given three lines of preamble puts
 * them in the first column and shifts everything. The text format is
 * where the human readable trimmings go.</p>
 */
public final class CsvReportWriter implements ReportWriter {

    private static final String DELIMITER_KEY = "report.csv.delimiter";
    private static final String DEFAULT_DELIMITER = ",";
    private static final String NEWLINE = System.lineSeparator();

    private final String delimiter;

    public CsvReportWriter() {
        this(ConfigLoader.getInstance().getString(DELIMITER_KEY, DEFAULT_DELIMITER));
    }

    public CsvReportWriter(String delimiter) {
        this.delimiter = delimiter == null || delimiter.isEmpty()
                ? DEFAULT_DELIMITER : delimiter;
    }

    @Override
    public String getExtension() {
        return ReportFormat.CSV.getExtension();
    }

    @Override
    public ReportFormat getFormat() {
        return ReportFormat.CSV;
    }

    public String getDelimiter() {
        return delimiter;
    }

    @Override
    public void write(ReportTable table, Appendable destination) throws IOException {
        if (table == null || destination == null) {
            throw new IllegalArgumentException("A report and a destination are required");
        }
        writeRow(table.getHeaders(), destination);
        for (List<String> row : table.getRows()) {
            writeRow(row, destination);
        }
    }

    private void writeRow(List<String> values, Appendable destination) throws IOException {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                destination.append(delimiter);
            }
            destination.append(escape(values.get(index)));
        }
        destination.append(NEWLINE);
    }

    /**
     * Quotes a value when leaving it bare would change its meaning.
     */
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuoting = value.contains(delimiter)
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0
                || !value.equals(value.trim());
        if (!needsQuoting) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
