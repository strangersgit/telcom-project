package com.amdocs.telecom.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Every keyboard read in the application goes through here.
 *
 * <p>Two things make this worth a class of its own. A password must not appear
 * on screen while it is typed, and the way to achieve that differs depending
 * on how the program was started: {@link System#console()} can mask the echo,
 * but it returns {@code null} when the process runs inside an IDE or with its
 * input redirected. Both cases are handled, and the caller never has to care
 * which one it got.</p>
 *
 * <p>Secrets are returned as {@code char[]} rather than {@code String} so the
 * caller can overwrite them once they have been used. A {@code String} would
 * sit in the constant pool until it happened to be collected.</p>
 */
public final class ConsoleReader {

    private final BufferedReader reader;
    private final PrintStream out;

    /** Null when the process has no real terminal, which is the IDE case. */
    private final java.io.Console console;

    /**
     * True when end of input was reached, which happens when the program is
     * driven from a file or a pipe. Menus use it to stop rather than spin.
     */
    private boolean inputExhausted;

    public ConsoleReader() {
        this(System.in, System.out);
    }

    /**
     * A reader over streams other than the keyboard, for driving the
     * application from a script or a pipe.
     */
    public ConsoleReader(java.io.InputStream in, PrintStream out) {
        this.reader = new BufferedReader(new InputStreamReader(in, Charset.defaultCharset()));
        this.out = out;
        // The masking console reads the process's own input, so it is only
        // the right source when this reader is reading from there too. A
        // reader wrapping any other stream must take its secrets from that
        // stream, or it would silently ignore what it was given and block
        // on the keyboard instead.
        this.console = in == System.in ? System.console() : null;
    }

    /**
     * Whether a password can be typed without appearing on screen. Reported
     * at the login prompt so the user knows which they are getting.
     */
    public boolean isEchoMaskingAvailable() {
        return console != null;
    }

    public boolean isInputExhausted() {
        return inputExhausted;
    }

    /* ---------- Text ---------- */

    /**
     * Reads one line, trimmed. Empty when the input has run out.
     */
    public Optional<String> readLine(String prompt) {
        out.print(prompt);
        out.flush();
        try {
            String line = reader.readLine();
            if (line == null) {
                inputExhausted = true;
                out.println();
                return Optional.empty();
            }
            return Optional.of(line.trim());
        } catch (IOException cause) {
            inputExhausted = true;
            AppLogger.error(ConsoleReader.class, "Could not read from the console", cause);
            return Optional.empty();
        }
    }

    /**
     * Keeps asking until the answer is not blank, or the input runs out.
     */
    public Optional<String> readRequired(String prompt) {
        return readMatching(prompt, value -> !value.isEmpty(), "This field cannot be left blank.");
    }

    /**
     * Keeps asking until the answer satisfies the test, or the input runs out.
     */
    public Optional<String> readMatching(String prompt, Predicate<String> acceptable, String complaint) {
        while (true) {
            Optional<String> answer = readLine(prompt);
            if (!answer.isPresent()) {
                return Optional.empty();
            }
            if (acceptable.test(answer.get())) {
                return answer;
            }
            out.println("  " + complaint);
        }
    }

    /* ---------- Secrets ---------- */

    /**
     * Reads a password without echoing it where the terminal allows that.
     *
     * <p>When masking is unavailable the characters are visible, and the
     * caller is told so rather than being left to assume otherwise.</p>
     *
     * @return the characters typed, which the caller should clear with
     *         {@link #clear(char[])} once they have been used
     */
    public Optional<char[]> readSecret(String prompt) {
        if (console != null) {
            char[] typed = console.readPassword("%s", prompt);
            if (typed == null) {
                inputExhausted = true;
                return Optional.empty();
            }
            return Optional.of(trimTrailing(typed));
        }
        return readLine(prompt).map(String::toCharArray);
    }

    /**
     * Overwrites a secret so it no longer sits in memory.
     */
    public static void clear(char[] secret) {
        if (secret != null) {
            java.util.Arrays.fill(secret, '\0');
        }
    }

    /* ---------- Numbers and dates ---------- */

    /**
     * Reads a whole number inside the given range, re-asking on anything else.
     */
    public Optional<Integer> readInt(String prompt, int minimum, int maximum) {
        while (true) {
            Optional<String> answer = readLine(prompt);
            if (!answer.isPresent()) {
                return Optional.empty();
            }
            try {
                int value = Integer.parseInt(answer.get());
                if (value < minimum || value > maximum) {
                    out.println("  Enter a number between " + minimum + " and " + maximum + ".");
                    continue;
                }
                return Optional.of(value);
            } catch (NumberFormatException ignored) {
                out.println("  '" + answer.get() + "' is not a number.");
            }
        }
    }

    public Optional<LocalDate> readDate(String prompt) {
        while (true) {
            Optional<String> answer = readLine(prompt);
            if (!answer.isPresent()) {
                return Optional.empty();
            }
            try {
                return Optional.of(LocalDate.parse(answer.get()));
            } catch (DateTimeParseException ignored) {
                out.println("  Enter the date as yyyy-MM-dd.");
            }
        }
    }

    /**
     * Asks a yes or no question. Anything starting with y or n is accepted.
     */
    public Optional<Boolean> readYesNo(String prompt) {
        while (true) {
            Optional<String> answer = readLine(prompt + " (y/n): ");
            if (!answer.isPresent()) {
                return Optional.empty();
            }
            String lowered = answer.get().toLowerCase();
            if (lowered.startsWith("y")) {
                return Optional.of(Boolean.TRUE);
            }
            if (lowered.startsWith("n")) {
                return Optional.of(Boolean.FALSE);
            }
            out.println("  Answer y or n.");
        }
    }

    /**
     * Presents a numbered list and returns the chosen element.
     */
    public <T> Optional<T> readChoice(String prompt, List<T> options) {
        if (options == null || options.isEmpty()) {
            return Optional.empty();
        }
        List<String> labels = new ArrayList<>();
        for (T option : options) {
            labels.add(String.valueOf(option));
        }
        for (int index = 0; index < labels.size(); index++) {
            out.println("    " + (index + 1) + ". " + labels.get(index));
        }
        return readInt(prompt, 1, options.size()).map(choice -> options.get(choice - 1));
    }

    /* ---------- Output helpers ---------- */

    public void println(String text) {
        out.println(text);
    }

    public void println() {
        out.println();
    }

    /**
     * Waits for the user to acknowledge before the screen scrolls on.
     */
    public void pause() {
        readLine("  Press Enter to continue ");
    }

    /**
     * A terminal cannot be cleared portably, so the screen is pushed up
     * instead. Enough blank lines to leave the previous menu out of sight.
     */
    public void clearScreen() {
        for (int line = 0; line < 3; line++) {
            out.println();
        }
    }

    /**
     * {@code Console.readPassword} hands back exactly what was typed, but a
     * redirected stream can leave a stray carriage return on the end.
     */
    private static char[] trimTrailing(char[] typed) {
        int length = typed.length;
        while (length > 0 && (typed[length - 1] == '\r' || typed[length - 1] == '\n')) {
            length--;
        }
        if (length == typed.length) {
            return typed;
        }
        char[] trimmed = new char[length];
        System.arraycopy(typed, 0, trimmed, 0, length);
        clear(typed);
        return trimmed;
    }
}
