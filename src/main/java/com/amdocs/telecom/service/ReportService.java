package com.amdocs.telecom.service;

import com.amdocs.telecom.report.ReportFormat;
import com.amdocs.telecom.report.ReportKind;
import com.amdocs.telecom.report.ReportTable;
import com.amdocs.telecom.service.analytics.TicketAnalytics;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The seven reports of section 18, and getting them onto disk.
 *
 * <p>Building a report and exporting it are kept apart on purpose. A
 * console wants to show a report before anybody decides to keep it, the
 * background generator wants to build several at once without touching
 * the filesystem, and a scheduled job wants to build and write in one
 * step. All three are served by {@link #generate} returning a
 * {@link ReportTable} and {@link #export} taking one.</p>
 *
 * <p>Reading only. Nothing here writes to the database, which is why none
 * of it takes a {@link com.amdocs.telecom.security.UserSession} and why
 * several reports can safely run on different threads at the same
 * time.</p>
 */
public interface ReportService {

    /**
     * Builds one report over everything.
     *
     * @throws IllegalArgumentException when a dated report is asked for
     *         without dates
     */
    ReportTable generate(ReportKind kind);

    /**
     * Builds one report over a window, for the kinds that take one.
     *
     * <p>Kinds that are about the present rather than a period ignore the
     * dates rather than refusing them, so a caller can pass a window
     * uniformly and let each report decide whether it means anything.</p>
     */
    ReportTable generate(ReportKind kind, LocalDate from, LocalDate to);

    /**
     * Builds a report from analytics already loaded.
     *
     * <p>What makes a report pack cheap: load the tickets once, then build
     * all seven from the same snapshot. It also means every report in the
     * pack describes the same instant, rather than seven reports each a
     * few hundred milliseconds apart with tickets moving underneath
     * them.</p>
     */
    ReportTable generate(ReportKind kind, TicketAnalytics analytics,
                         LocalDate from, LocalDate to);

    /**
     * Builds every report, all from one snapshot.
     */
    Map<ReportKind, ReportTable> generateAll(LocalDate from, LocalDate to);

    /**
     * Loads the data the reports are built from.
     */
    TicketAnalytics loadAnalytics();

    /**
     * Writes a report to a file and says where it went.
     */
    Path export(ReportTable table, ReportFormat format);

    /**
     * Builds a report and writes it in each format asked for.
     */
    List<Path> generateAndExport(ReportKind kind, LocalDate from, LocalDate to,
                                 ReportFormat... formats);

    /**
     * A report rendered as text, without writing anything.
     */
    String preview(ReportTable table, ReportFormat format);
}
