package com.amdocs.telecom.controller;

import com.amdocs.telecom.exception.TSATMSException;
import com.amdocs.telecom.security.Permission;
import com.amdocs.telecom.security.UserSession;
import com.amdocs.telecom.util.AppConstants;
import com.amdocs.telecom.util.AppLogger;
import com.amdocs.telecom.util.ConsoleReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A numbered menu and the loop that runs it.
 *
 * <p>Every screen in sections 13, 14 and 15 is the same shape: a title, a
 * numbered list, read a number, do the thing, come back. Written once
 * here, each dashboard is left as a declaration of what its options are
 * rather than four copies of the same loop differing in their typos.</p>
 *
 * <h3>Three things the loop has to get right</h3>
 *
 * <p><b>A failed action must not end the session.</b> If assigning an
 * engineer fails because somebody else took the ticket first, the user
 * should see why and still be on the dashboard. So every action runs
 * inside a catch, and only the menu itself can decide to stop.</p>
 *
 * <p><b>Running out of input must not spin.</b> When the program is driven
 * from a file or a pipe rather than a keyboard, a menu that re-prompts
 * forever is an infinite loop. {@link ConsoleReader#isInputExhausted()}
 * ends it.</p>
 *
 * <p><b>An expired session must not keep working.</b> The check happens at
 * the top of each turn, because a dashboard left open over lunch is
 * exactly the case the idle timeout exists for.</p>
 */
public final class Menu {

    private final String title;
    private final List<MenuOption> options;

    private Menu(String title, List<MenuOption> options) {
        this.title = title;
        this.options = Collections.unmodifiableList(new ArrayList<MenuOption>(options));
    }

    public static Builder titled(String title) {
        return new Builder(title);
    }

    public String getTitle() {
        return title;
    }

    public List<MenuOption> getOptions() {
        return options;
    }

    public int size() {
        return options.size();
    }

    /**
     * The menu as it appears on screen.
     *
     * <p>Options the role may not choose are shown and marked rather than
     * removed. Removing them would renumber the rest, so the same
     * dashboard would present different numbers to different people and
     * neither would match the case study. Saying "not available to your
     * role" also tells the user something true, where a silently shorter
     * list tells them nothing.</p>
     */
    public String render(UserSession session) {
        String newLine = System.lineSeparator();
        StringBuilder screen = new StringBuilder();
        screen.append("  ").append(AppConstants.LINE_SINGLE).append(newLine);
        screen.append("  ").append(title).append(newLine);
        screen.append("  ").append(AppConstants.LINE_SINGLE).append(newLine);
        for (int index = 0; index < options.size(); index++) {
            MenuOption option = options.get(index);
            String line = String.format("  %2d. %s", index + 1, option.getLabel());
            if (!option.isAvailableTo(session)) {
                line = String.format("  %2d. %-40s (not available to your role)",
                        index + 1, option.getLabel());
            }
            screen.append(line).append(newLine);
        }
        screen.append("  ").append(AppConstants.LINE_SINGLE);
        return screen.toString();
    }

    /**
     * Shows the menu and acts on choices until the user picks the exit
     * option, the session expires, or the input runs out.
     */
    public void runUntilExit(ConsoleReader console, UserSession session) {
        while (true) {
            if (session != null && !session.isActive()) {
                console.println();
                console.println("  Your session has ended. Please sign in again.");
                console.println();
                return;
            }

            console.println();
            console.println(render(session));

            Optional<Integer> choice = console.readInt(
                    "  Choose an option (1-" + options.size() + "): ", 1, options.size());
            if (!choice.isPresent()) {
                // Only reachable when the input ran out, since readInt
                // re-asks on anything else.
                return;
            }

            MenuOption option = options.get(choice.get() - 1);
            if (!option.isAvailableTo(session)) {
                console.println();
                console.println("  " + option.describeRefusal());
                console.println();
                continue;
            }

            boolean finished = dispatch(option, console, session);
            if (finished || console.isInputExhausted()) {
                return;
            }
        }
    }

    /**
     * Runs one option and reports whether the menu should close.
     *
     * <p>Business refusals are printed as the service worded them. An
     * unexpected failure is printed too, and logged with its stack trace,
     * because a user seeing only "something went wrong" and an empty log
     * is the worst of both.</p>
     */
    private boolean dispatch(MenuOption option, ConsoleReader console, UserSession session) {
        try {
            option.perform();
        } catch (TSATMSException refused) {
            console.println();
            console.println("  " + refused.toDisplayString());
            AppLogger.warn(Menu.class, "'" + option.getLabel() + "' refused: "
                    + refused.getMessage());
        } catch (RuntimeException failure) {
            console.println();
            console.println("  That did not work: " + failure.getMessage());
            console.println("  The details are in the log.");
            AppLogger.error(Menu.class, "'" + option.getLabel() + "' failed", failure);
        }

        if (session != null) {
            session.touch();
        }
        if (option.isTerminal()) {
            return true;
        }
        console.println();
        console.pause();
        return false;
    }

    /**
     * Collects the options in the order they will be numbered.
     */
    public static final class Builder {

        private final String title;
        private final List<MenuOption> options = new ArrayList<MenuOption>();

        private Builder(String title) {
            if (title == null || title.trim().isEmpty()) {
                throw new IllegalArgumentException("A menu needs a title");
            }
            this.title = title.trim();
        }

        public Builder option(String label, MenuOption.MenuAction action) {
            options.add(MenuOption.of(label, action));
            return this;
        }

        public Builder guarded(String label, Permission required,
                               MenuOption.MenuAction action) {
            options.add(MenuOption.guarded(label, required, action));
            return this;
        }

        public Builder exit(String label) {
            options.add(MenuOption.exit(label));
            return this;
        }

        public Builder exit(String label, MenuOption.MenuAction action) {
            options.add(MenuOption.exit(label, action));
            return this;
        }

        public Menu build() {
            if (options.isEmpty()) {
                throw new IllegalStateException("A menu needs at least one option: " + title);
            }
            boolean hasExit = false;
            for (MenuOption option : options) {
                hasExit |= option.isTerminal();
            }
            if (!hasExit) {
                // Without one there is no way off the screen but killing
                // the program, which is not a design decision anybody makes
                // on purpose.
                throw new IllegalStateException("A menu needs a way out: " + title);
            }
            return new Menu(title, options);
        }
    }
}
