package com.amdocs.telecom.controller;

import com.amdocs.telecom.report.ReportFormat;
import com.amdocs.telecom.report.ReportGenerator;
import com.amdocs.telecom.report.ReportKind;
import com.amdocs.telecom.report.ReportResult;
import com.amdocs.telecom.report.ReportTable;
import com.amdocs.telecom.security.Permission;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.service.ReportService;
import com.amdocs.telecom.util.AppConstants;
import com.amdocs.telecom.util.ConsoleReader;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The "Generate Reports" screen, shared by the service desk and the
 * manager.
 *
 * <p>Both dashboards offer it and both offer the same seven reports, so
 * it lives in one place rather than being written twice and drifting.
 * The permission check is still each dashboard's, because the two roles
 * reach it through different menu lines.</p>
 *
 * <h3>Why "all seven" goes through the thread pool</h3>
 *
 * <p>One report is a single read and a table; running it on a pool would
 * be ceremony. Seven is different: each is independent, each takes a
 * moment, and section 17 asks for exactly this. So a single report is
 * built inline and the whole pack is handed to
 * {@link ReportGenerator}, which is the honest use of a
 * {@code Callable} and a {@code Future} rather than a demonstration
 * bolted on somewhere it is not needed.</p>
 */
public final class ReportConsole {

    /** Enough to overlap seven reports without flooding the database. */
    private static final int REPORT_THREADS = 4;

    private final UserSession session;
    private final ConsoleReader console;
    private final ReportService reports;

    public ReportConsole(UserSession session, ConsoleReader console, ReportService reports) {
        this.session = session;
        this.console = console;
        this.reports = reports;
    }

    /**
     * Offers the seven reports of section 18, plus running them all at
     * once.
     */
    public void run() {
        List<ReportKind> kinds = Arrays.asList(ReportKind.values());

        console.println();
        console.println("  Reports");
        console.println("  " + AppConstants.LINE_SINGLE);
        for (int index = 0; index < kinds.size(); index++) {
            console.println(String.format("  %2d. %-30s %s", index + 1,
                    kinds.get(index).getDisplayName(), kinds.get(index).getSummary()));
        }
        console.println(String.format("  %2d. %-30s %s", kinds.size() + 1,
                "All of them", "Run the whole pack on a thread pool"));
        console.println("  " + AppConstants.LINE_SINGLE);

        Optional<Integer> choice = console.readInt(
                "  Choose a report (1-" + (kinds.size() + 1) + "): ", 1, kinds.size() + 1);
        if (!choice.isPresent()) {
            return;
        }

        if (choice.get() == kinds.size() + 1) {
            runAll(kinds);
            return;
        }
        runOne(kinds.get(choice.get() - 1));
    }

    /* ---------- One report ---------- */

    private void runOne(ReportKind kind) {
        LocalDate from = null;
        LocalDate to = null;
        if (kind.isDated()) {
            // Only the volume report is about a period, and it defaults to
            // the last thirty days, so the dates are offered rather than
            // demanded.
            console.println();
            console.println("  Leave both dates blank for the last 30 days.");
            from = console.readLine("  From (yyyy-MM-dd): ")
                    .filter(text -> !text.isEmpty()).map(LocalDate::parse).orElse(null);
            to = console.readLine("  To   (yyyy-MM-dd): ")
                    .filter(text -> !text.isEmpty()).map(LocalDate::parse).orElse(null);
        }

        ReportTable table = reports.generate(kind, from, to);
        show(table);
        offerExport(table);
    }

    /* ---------- All seven ---------- */

    private void runAll(List<ReportKind> kinds) {
        ReportGenerator generator = new ReportGenerator(reports, REPORT_THREADS);
        generator.start();
        try {
            long startedAt = System.currentTimeMillis();
            Map<ReportKind, ReportResult> pack = generator.runAll(kinds);
            long took = System.currentTimeMillis() - startedAt;

            console.println();
            console.println("  Ran " + pack.size() + " report(s) in " + took + "ms");
            console.println("  " + AppConstants.LINE_SINGLE);
            console.println(String.format("  %-30s %8s %8s  %s",
                    "REPORT", "ROWS", "TOOK", "THREAD"));
            for (ReportKind kind : kinds) {
                ReportResult result = pack.get(kind);
                if (result == null) {
                    console.println(String.format("  %-30s %8s", kind.getDisplayName(),
                            "failed"));
                    continue;
                }
                console.println(String.format("  %-30s %8d %6dms  %s",
                        kind.getDisplayName(), result.getRowCount(), result.getTookMillis(),
                        result.getProducedBy()));
            }
            console.println("  " + AppConstants.LINE_SINGLE);

            offerExportAll(pack, kinds);
        } finally {
            // The pool is created for this one request. Leaving it running
            // would accumulate a set of threads every time somebody opened
            // the screen.
            generator.stop();
        }
    }

    /* ---------- Showing and exporting ---------- */

    private void show(ReportTable table) {
        console.println();
        for (String line : reports.preview(table, ReportFormat.TEXT).split("\\R", -1)) {
            console.println("  " + line);
        }
    }

    private void offerExport(ReportTable table) {
        if (!session.hasPermission(Permission.EXPORT_REPORTS)) {
            return;
        }
        console.println();
        SaveChoice choice = askFormat();
        if (choice == SaveChoice.NONE) {
            return;
        }
        report(write(table, choice));
    }

    private void offerExportAll(Map<ReportKind, ReportResult> pack, List<ReportKind> kinds) {
        if (!session.hasPermission(Permission.EXPORT_REPORTS)) {
            return;
        }
        console.println();
        SaveChoice choice = askFormat();
        if (choice == SaveChoice.NONE) {
            return;
        }

        List<Path> written = new ArrayList<Path>();
        for (ReportKind kind : kinds) {
            ReportResult result = pack.get(kind);
            if (result != null) {
                written.addAll(write(result.getTable(), choice));
            }
        }
        report(written);
    }

    /**
     * What the user wants done with a report once they have seen it.
     *
     * <p>A named choice rather than a nullable {@code ReportFormat},
     * because "both" and "neither" are answers the format enum cannot
     * express and an absent format would have to mean one of them.</p>
     */
    private enum SaveChoice {
        CSV, TEXT, BOTH, NONE
    }

    private SaveChoice askFormat() {
        console.println("  1. CSV        2. Text        3. Both        4. Do not save");
        Optional<Integer> choice = console.readInt("  Save as (1-4): ", 1, 4);
        if (!choice.isPresent()) {
            return SaveChoice.NONE;
        }
        switch (choice.get()) {
            case 1:
                return SaveChoice.CSV;
            case 2:
                return SaveChoice.TEXT;
            case 3:
                return SaveChoice.BOTH;
            default:
                return SaveChoice.NONE;
        }
    }

    private List<Path> write(ReportTable table, SaveChoice choice) {
        List<Path> written = new ArrayList<Path>(2);
        if (choice == SaveChoice.CSV || choice == SaveChoice.BOTH) {
            written.add(reports.export(table, ReportFormat.CSV));
        }
        if (choice == SaveChoice.TEXT || choice == SaveChoice.BOTH) {
            written.add(reports.export(table, ReportFormat.TEXT));
        }
        return written;
    }

    private void report(List<Path> written) {
        console.println();
        if (written.isEmpty()) {
            console.println("  Nothing was written.");
            return;
        }
        console.println("  Wrote " + written.size() + " file(s):");
        for (Path file : written) {
            console.println("    " + file);
        }
        console.println();
    }
}
