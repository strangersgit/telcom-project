package com.amdocs.telecom.controller;

import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.enums.DescribableEnum;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.util.AppConstants;
import com.amdocs.telecom.util.AppLogger;
import com.amdocs.telecom.util.ConsoleReader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * What every role's dashboard has in common.
 *
 * <h3>The shape is fixed here, the content by the subclass</h3>
 *
 * <p>{@link #open()} is final: banner, menu, loop, sign off, in that
 * order, the same for all four roles. A subclass supplies its title and
 * its options and nothing else. That is the template method pattern, and
 * the reason for it is that four dashboards each owning their own copy of
 * this sequence would be four places for the session check to be
 * forgotten.</p>
 *
 * <h3>The prompts are here too</h3>
 *
 * <p>Asking for a ticket number, picking an enum from a list, printing a
 * table with a heading: sections 13, 14 and 15 do all three repeatedly.
 * Each is a few lines, and each is a few lines that has to behave the
 * same everywhere, particularly when the user presses Enter on an empty
 * prompt to back out. Written once, backing out works the same on every
 * screen.</p>
 */
public abstract class Dashboard {

    /** How many rows a list shows before it asks the user to narrow it. */
    protected static final int LIST_LIMIT = 50;

    protected final UserSession session;
    protected final ConsoleReader console;

    protected Dashboard(UserSession session, ConsoleReader console) {
        if (session == null || console == null) {
            throw new IllegalArgumentException("A dashboard needs a session and a console");
        }
        this.session = session;
        this.console = console;
    }

    /** The name at the top of the screen. */
    public abstract String getTitle();

    /** The numbered options this role is offered. */
    protected abstract Menu buildMenu();

    /**
     * Runs the dashboard until the user logs out.
     */
    public final void open() {
        AppLogger.info(getClass(), session.getUsername() + " opened " + getTitle());
        printBanner();
        buildMenu().runUntilExit(console, session);
        AppLogger.info(getClass(), session.getUsername() + " left " + getTitle());
    }

    private void printBanner() {
        console.println();
        console.println("  " + AppConstants.LINE_DOUBLE);
        console.println("  " + getTitle().toUpperCase());
        console.println("  " + session.describe());
        console.println("  " + AppConstants.LINE_DOUBLE);
    }

    /* ---------- Printing ---------- */

    protected void heading(String text) {
        console.println();
        console.println("  " + text);
        console.println("  " + AppConstants.LINE_SINGLE);
    }

    protected void note(String text) {
        console.println("  " + text);
    }

    protected void blank() {
        console.println();
    }

    /**
     * Prints a table, or says plainly that there is nothing in it.
     *
     * <p>An empty list printed as an empty screen leaves the user unsure
     * whether the question was understood.</p>
     */
    protected <T> void table(String title, String columnHeading, List<T> rows,
                             java.util.function.Function<T, String> asRow,
                             String whenEmpty) {
        heading(title);
        if (rows == null || rows.isEmpty()) {
            note(whenEmpty);
            blank();
            return;
        }
        note(columnHeading);
        int shown = 0;
        for (T row : rows) {
            if (shown++ == LIST_LIMIT) {
                note("... and " + (rows.size() - LIST_LIMIT) + " more");
                break;
            }
            note(asRow.apply(row));
        }
        console.println("  " + AppConstants.LINE_SINGLE);
        note(rows.size() + " row(s)");
        blank();
    }

    /**
     * Prints a multi-line block indented to match everything else.
     */
    protected void block(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (String line : text.split("\\R", -1)) {
            console.println("  " + line);
        }
    }

    /* ---------- Asking ---------- */

    /**
     * Asks for a ticket number, allowing an empty answer to mean "never
     * mind".
     */
    protected Optional<String> askTicketNumber() {
        return askTicketNumber("  Ticket number (or blank to go back): ");
    }

    protected Optional<String> askTicketNumber(String prompt) {
        return console.readLine(prompt).filter(answer -> !answer.isEmpty())
                .map(answer -> answer.toUpperCase());
    }

    /**
     * Asks for text that must not be blank, allowing an empty answer to
     * back out.
     */
    protected Optional<String> askText(String prompt) {
        return console.readLine(prompt).filter(answer -> !answer.isEmpty());
    }

    /**
     * Offers the constants of an enum by their display names and returns
     * the one chosen.
     *
     * <p>Typing {@code NETWORK_OUTAGE} correctly is not a skill a screen
     * should require, and a mistyped one is a validation failure the user
     * has to read and recover from. A numbered list cannot be mistyped
     * into something invalid.</p>
     */
    protected <E extends Enum<E> & DescribableEnum> Optional<E> askEnum(String prompt,
                                                                       E[] values) {
        return askEnum(prompt, Arrays.asList(values));
    }

    protected <E extends Enum<E> & DescribableEnum> Optional<E> askEnum(String prompt,
                                                                       List<E> values) {
        if (values.isEmpty()) {
            return Optional.empty();
        }
        List<String> labels = new ArrayList<String>(values.size());
        for (E value : values) {
            labels.add(value.getDisplayName());
        }
        blank();
        for (int index = 0; index < labels.size(); index++) {
            note(String.format("%3d. %s", index + 1, labels.get(index)));
        }
        return console.readInt(prompt, 1, values.size()).map(choice -> values.get(choice - 1));
    }

    /**
     * Offers a list of anything that can describe itself in one line.
     */
    protected <T extends Displayable> Optional<T> askChoice(String prompt, List<T> values) {
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        blank();
        for (int index = 0; index < values.size(); index++) {
            note(String.format("%3d. %s", index + 1, values.get(index).toSummaryLine()));
        }
        return console.readInt(prompt, 1, values.size()).map(choice -> values.get(choice - 1));
    }

    /**
     * Asks the user to confirm something that cannot be undone.
     */
    protected boolean confirm(String question) {
        return console.readYesNo("  " + question).orElse(Boolean.FALSE);
    }

    /* ---------- Reporting back ---------- */

    protected void done(String text) {
        blank();
        note(text);
    }

    protected void cancelled() {
        blank();
        note("Nothing was changed.");
    }
}
