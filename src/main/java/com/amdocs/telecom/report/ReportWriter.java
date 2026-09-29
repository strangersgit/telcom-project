package com.amdocs.telecom.report;

import java.io.IOException;

/**
 * Turns a finished report into text of some format.
 *
 * <p>Section 18 asks for export to CSV and TXT, and the two differ only in
 * how the same table is laid out. That is the Strategy pattern's exact
 * shape: one thing to do, several interchangeable ways of doing it, chosen
 * at the moment of use. {@link ReportExporter} holds a writer and does not
 * know or care which; adding a third format means adding a class and a
 * constant, and changing nothing that already works.</p>
 *
 * <p>Writers append to an {@link Appendable} rather than returning a
 * string, so a large report can be streamed straight to a file without
 * being assembled in memory first. {@link #render(ReportTable)} is there
 * for the console, where the report is small and a string is what is
 * wanted.</p>
 */
public interface ReportWriter {

    /**
     * The file extension this format uses, without the dot.
     */
    String getExtension();

    /**
     * The format this writer produces.
     */
    ReportFormat getFormat();

    /**
     * Writes the report to the given destination.
     */
    void write(ReportTable table, Appendable destination) throws IOException;

    /**
     * The whole report as a string, for a console or a test.
     *
     * <p>A {@code default} method because it is the same for every format:
     * write into a buffer and hand back what landed there. The
     * {@code IOException} that {@link #write} declares cannot happen
     * against a {@link StringBuilder}, so it is turned into an unchecked
     * failure rather than pushed onto every caller.</p>
     */
    default String render(ReportTable table) {
        StringBuilder buffer = new StringBuilder();
        try {
            write(table, buffer);
        } catch (IOException impossible) {
            throw new IllegalStateException(
                    "Writing a report into memory failed, which should not be possible",
                    impossible);
        }
        return buffer.toString();
    }
}
