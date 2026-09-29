package com.amdocs.telecom.report;

import com.amdocs.telecom.exception.ErrorCode;
import com.amdocs.telecom.exception.TSATMSException;
import com.amdocs.telecom.util.AppConstants;
import com.amdocs.telecom.util.AppLogger;
import com.amdocs.telecom.util.ConfigLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes reports to files, which is the "Export to CSV/TXT" of section 18
 * and the file handling the case study asks to see.
 *
 * <h3>Where the files go, and why the name is what it is</h3>
 *
 * <p>The directory comes from {@code report.output.directory} rather than
 * being hard coded, because where reports belong is a deployment decision
 * and not a code one. It is created on first use: failing an export
 * because a directory is missing, when creating it is one call, would be
 * pedantry.</p>
 *
 * <p>Each file is named for its report and stamped with the second it was
 * written, so exports accumulate rather than overwrite. Somebody
 * comparing this morning's SLA position with this afternoon's needs both
 * files to still exist, and a fixed name would have thrown the first one
 * away.</p>
 *
 * <h3>Encoding is stated, not inherited</h3>
 *
 * <p>UTF-8 explicitly, every time. Left to the platform default, the same
 * report exported on this machine and on a server would produce different
 * bytes for the same customer name, and the one that got it wrong would
 * be whichever machine nobody tested on.</p>
 */
public final class ReportExporter {

    private static final String DIRECTORY_KEY = "report.output.directory";
    private static final String DEFAULT_DIRECTORY = "reports";

    private final Path directory;

    public ReportExporter() {
        this(ConfigLoader.getInstance().getString(DIRECTORY_KEY, DEFAULT_DIRECTORY));
    }

    public ReportExporter(String directory) {
        this.directory = Paths.get(directory == null || directory.trim().isEmpty()
                ? DEFAULT_DIRECTORY : directory.trim());
    }

    /**
     * Where exports are written.
     */
    public Path getDirectory() {
        return directory;
    }

    /**
     * Writes one report and returns the file it landed in.
     *
     * @throws TSATMSException when the file cannot be written, which is a
     *         real failure the caller has to hear about rather than a
     *         silent no-op
     */
    public Path export(ReportTable table, ReportFormat format) {
        if (table == null || format == null) {
            throw new IllegalArgumentException("A report and a format are required");
        }
        Path file = directory.resolve(fileNameFor(table, format));
        ReportWriter writer = format.newWriter();
        try {
            Files.createDirectories(directory);
            // try-with-resources: the file handle is released whether the
            // write succeeds or throws halfway through a large report.
            try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write(table, out);
            }
        } catch (IOException failure) {
            throw new TSATMSException(ErrorCode.FILE_WRITE_FAILED,
                    "Could not write " + table.getKind().getDisplayName() + " to " + file,
                    failure);
        }
        AppLogger.info(ReportExporter.class, "Exported " + table.getKind().getDisplayName()
                + " (" + table.getRowCount() + " row(s)) to " + file);
        return file;
    }

    /**
     * Writes the same report in every format asked for.
     *
     * <p>Section 18 says CSV/TXT, and the honest reading of the slash is
     * that a user should be able to have both from one run rather than
     * having to generate the report twice.</p>
     */
    public List<Path> exportAll(ReportTable table, ReportFormat... formats) {
        List<Path> written = new ArrayList<Path>();
        if (formats == null || formats.length == 0) {
            written.add(export(table, ReportFormat.TEXT));
            return written;
        }
        for (ReportFormat format : formats) {
            written.add(export(table, format));
        }
        return written;
    }

    /**
     * The report as a string, without touching the disk, for a console
     * that wants to show it before anybody decides to keep it.
     */
    public String preview(ReportTable table, ReportFormat format) {
        if (table == null) {
            throw new IllegalArgumentException("A report is required");
        }
        return (format == null ? ReportFormat.TEXT : format).newWriter().render(table);
    }

    private static String fileNameFor(ReportTable table, ReportFormat format) {
        return table.getKind().getFileStem() + "_"
                + table.getGeneratedAt().format(AppConstants.FILE_TIMESTAMP)
                + "." + format.getExtension();
    }
}
