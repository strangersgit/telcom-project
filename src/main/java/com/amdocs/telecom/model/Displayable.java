package com.amdocs.telecom.model;

import com.amdocs.telecom.util.AppConstants;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Console rendering contract shared by every entity.
 *
 * <p>Each entity supplies its own one line summary; the {@code default}
 * method builds a detail block from it, and the {@code static} helpers give
 * every implementation the same formatting for dates and absent values. This
 * is where the case study's requirement for default and static interface
 * methods is met on the model side.</p>
 */
public interface Displayable {

    /**
     * Single line suitable for a list or table row.
     */
    String toSummaryLine();

    /**
     * Multi line form for a detail screen. Entities with more to show
     * override this; the rest inherit the summary.
     */
    default String toDetailBlock() {
        return toSummaryLine();
    }

    /**
     * Renders a timestamp in the case study's format, 08-Aug-2026 18:30.
     */
    static String formatDateTime(LocalDateTime value) {
        return value == null ? "-" : value.format(AppConstants.DISPLAY_DATE_TIME);
    }

    static String formatDate(LocalDate value) {
        return value == null ? "-" : value.format(AppConstants.DISPLAY_DATE);
    }

    /**
     * Substitutes a dash for anything absent, so console columns never show
     * the word "null".
     */
    static String orDash(Object value) {
        if (value == null) {
            return "-";
        }
        String text = String.valueOf(value);
        return text.trim().isEmpty() ? "-" : text;
    }

    /**
     * Formats a label and value as the case study's sample ticket does, with
     * the values aligned in a column.
     */
    static String labelled(String label, Object value) {
        return String.format("%-14s: %s", label, orDash(value));
    }

    /**
     * Shortens free text so it fits a fixed width console column.
     */
    static String truncate(String value, int maxLength) {
        if (value == null) {
            return "-";
        }
        String flattened = value.replaceAll("\\s+", " ").trim();
        if (flattened.length() <= maxLength) {
            return flattened;
        }
        return flattened.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
