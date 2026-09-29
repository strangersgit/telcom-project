package com.amdocs.telecom.util;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Application wide logging facade over {@code java.util.logging}.
 *
 * <p>Two sinks are configured from {@code application.properties}: a rotating
 * file that captures everything at the configured level, and a console sink
 * held at a higher threshold so routine log lines never scroll through the
 * interactive menus.</p>
 *
 * <p>This class deliberately does not depend on anything except
 * {@link ConfigLoader}, so it can be brought up first during startup.</p>
 */
public final class AppLogger {

    private static final String LOGGER_NAME = "com.amdocs.telecom";
    private static final Logger LOGGER = Logger.getLogger(LOGGER_NAME);

    private static volatile boolean initialised;
    private static String logFilePath = "(file logging disabled)";
    private static String initialisationWarning;

    private AppLogger() {
        throw new AssertionError("AppLogger is not instantiable");
    }

    /**
     * Wires up the handlers. Safe to call more than once; later calls are
     * ignored.
     */
    public static synchronized void initialize() {
        if (initialised) {
            return;
        }
        initialised = true;

        ConfigLoader config = ConfigLoader.getInstance();

        LOGGER.setUseParentHandlers(false);
        for (Handler existing : LOGGER.getHandlers()) {
            LOGGER.removeHandler(existing);
        }

        Level fileLevel = parseLevel(config.getString("log.level", "INFO"), Level.INFO);
        LOGGER.setLevel(fileLevel);

        if (config.getBoolean("log.file.enabled", true)) {
            attachFileHandler(config, fileLevel);
        }

        if (config.getBoolean("log.console.enabled", true)) {
            Level consoleLevel = parseLevel(config.getString("log.console.level", "WARNING"), Level.WARNING);
            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setLevel(consoleLevel);
            consoleHandler.setFormatter(new SingleLineFormatter());
            LOGGER.addHandler(consoleHandler);
        }
    }

    private static void attachFileHandler(ConfigLoader config, Level fileLevel) {
        String directory = config.getString("log.directory", "logs");
        String fileName = config.getString("log.file", "tsatms.log");
        int sizeLimit = config.getInt("log.file.size.bytes", 5 * 1024 * 1024);
        int fileCount = Math.max(1, config.getInt("log.file.count", 5));

        File logDirectory = new File(directory);
        if (!logDirectory.isDirectory() && !logDirectory.mkdirs()) {
            initialisationWarning = "Could not create log directory " + logDirectory.getAbsolutePath()
                    + "; file logging is disabled for this run.";
            return;
        }

        String pattern = new File(logDirectory, fileName).getPath();
        try {
            FileHandler fileHandler = new FileHandler(pattern, sizeLimit, fileCount, true);
            fileHandler.setLevel(fileLevel);
            fileHandler.setFormatter(new SingleLineFormatter());
            LOGGER.addHandler(fileHandler);
            // With a rotation count above one, FileHandler appends the
            // generation number to the name it actually writes to.
            logFilePath = fileCount > 1 ? pattern + ".0" : pattern;
        } catch (IOException cause) {
            initialisationWarning = "Could not open log file " + pattern
                    + " (" + cause.getMessage() + "); file logging is disabled for this run.";
        }
    }

    private static Level parseLevel(String raw, Level fallback) {
        try {
            return Level.parse(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    /* ---------- Logging entry points ---------- */

    public static void debug(String message) {
        log(Level.FINE, null, message, null);
    }

    public static void debug(Class<?> source, String message) {
        log(Level.FINE, source, message, null);
    }

    public static void info(String message) {
        log(Level.INFO, null, message, null);
    }

    public static void info(Class<?> source, String message) {
        log(Level.INFO, source, message, null);
    }

    public static void warn(String message) {
        log(Level.WARNING, null, message, null);
    }

    public static void warn(Class<?> source, String message) {
        log(Level.WARNING, source, message, null);
    }

    public static void warn(Class<?> source, String message, Throwable thrown) {
        log(Level.WARNING, source, message, thrown);
    }

    public static void error(String message) {
        log(Level.SEVERE, null, message, null);
    }

    public static void error(String message, Throwable thrown) {
        log(Level.SEVERE, null, message, thrown);
    }

    public static void error(Class<?> source, String message, Throwable thrown) {
        log(Level.SEVERE, source, message, thrown);
    }

    private static void log(Level level, Class<?> source, String message, Throwable thrown) {
        if (!initialised) {
            initialize();
        }
        if (!LOGGER.isLoggable(level)) {
            return;
        }
        LogRecord record = new LogRecord(level, message);
        record.setLoggerName(LOGGER_NAME);
        record.setSourceClassName(source == null ? "TSATMS" : source.getSimpleName());
        record.setParameters(new Object[]{Thread.currentThread().getName()});
        if (thrown != null) {
            record.setThrown(thrown);
        }
        LOGGER.log(record);
    }

    /* ---------- Diagnostics ---------- */

    /**
     * Path of the file currently being written to.
     */
    public static String getLogFilePath() {
        return logFilePath;
    }

    /**
     * Any problem encountered while setting logging up, or {@code null} when
     * initialisation was clean. Reported by the startup banner rather than
     * thrown, since a missing log file should not stop the application.
     */
    public static String getInitialisationWarning() {
        return initialisationWarning;
    }

    /**
     * Flushes and closes the handlers during shutdown.
     */
    public static synchronized void shutdown() {
        for (Handler handler : LOGGER.getHandlers()) {
            handler.flush();
            handler.close();
            LOGGER.removeHandler(handler);
        }
        initialised = false;
    }

    /**
     * Renders one record per line as
     * {@code 2026-09-25 21:53:11.123 [main] INFO    DBConnection - message}.
     */
    private static final class SingleLineFormatter extends Formatter {

        @Override
        public String format(LogRecord record) {
            LocalDateTime timestamp = Instant.ofEpochMilli(record.getMillis())
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();

            Object[] parameters = record.getParameters();
            String threadName = (parameters != null && parameters.length > 0 && parameters[0] != null)
                    ? String.valueOf(parameters[0])
                    : "main";

            StringBuilder builder = new StringBuilder(160);
            builder.append(timestamp.format(AppConstants.LOG_TIMESTAMP))
                    .append(" [").append(threadName).append("] ")
                    .append(String.format("%-7s", record.getLevel().getName()))
                    .append(' ')
                    .append(record.getSourceClassName())
                    .append(" - ")
                    // Read the raw message rather than calling formatMessage:
                    // the parameter slot carries the thread name, not
                    // MessageFormat arguments.
                    .append(record.getMessage())
                    .append(System.lineSeparator());

            if (record.getThrown() != null) {
                StringWriter stackTrace = new StringWriter();
                try (PrintWriter writer = new PrintWriter(stackTrace)) {
                    record.getThrown().printStackTrace(writer);
                }
                builder.append(stackTrace);
            }
            return builder.toString();
        }
    }
}
