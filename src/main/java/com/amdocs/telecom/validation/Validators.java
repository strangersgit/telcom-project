package com.amdocs.telecom.validation;

/**
 * Field level checks shared by the entity validators.
 *
 * <p>Each one appends to a {@link ValidationResult} rather than returning a
 * verdict, so a caller can run a dozen of them and then decide once whether
 * to complain.</p>
 *
 * <p>The length limits callers pass in come from the column widths in
 * {@code 01_schema.sql}. Checking them here rather than letting the database
 * truncate or reject means the user is told which field is too long, in
 * their own terms, instead of seeing a SQL error.</p>
 */
public final class Validators {

    private Validators() {
        throw new AssertionError("Validators is not instantiable");
    }

    /**
     * Mandatory free text, neither blank nor longer than the column holding
     * it.
     */
    public static void requireText(ValidationResult result, String label, String value,
                                   int minLength, int maxLength) {
        if (isBlank(value)) {
            result.reject(label + " is required");
            return;
        }
        String trimmed = value.trim();
        if (trimmed.length() < minLength) {
            result.reject(label + " must be at least " + minLength + " characters");
        }
        if (trimmed.length() > maxLength) {
            result.reject(label + " must be " + maxLength + " characters or fewer, but is "
                    + trimmed.length());
        }
    }

    /**
     * Free text that may be left out entirely, but must fit if supplied.
     */
    public static void optionalText(ValidationResult result, String label, String value,
                                    int maxLength) {
        if (!isBlank(value) && value.trim().length() > maxLength) {
            result.reject(label + " must be " + maxLength + " characters or fewer, but is "
                    + value.trim().length());
        }
    }

    /**
     * A mandatory choice, such as a category or a resolution code.
     */
    public static void requireValue(ValidationResult result, String label, Object value) {
        if (value == null) {
            result.reject(label + " is required");
        }
    }

    public static void requireRange(ValidationResult result, String label, int value,
                                    int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            result.reject(label + " must be between " + minimum + " and " + maximum
                    + ", but was " + value);
        }
    }

    /**
     * A database key supplied by a caller. Zero and negative values are
     * refused here so they never reach a query.
     */
    public static void requireIdentifier(ValidationResult result, String label, Long value) {
        if (value == null) {
            result.reject(label + " is required");
        } else if (value <= 0L) {
            result.reject(label + " is not a valid identifier");
        }
    }

    /**
     * Java 8 has no {@code String.isBlank}.
     */
    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Trimmed text, or null when there was nothing but whitespace. Used
     * before a write so a column holds either real content or NULL, never a
     * string of spaces.
     */
    public static String trimToNull(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value.trim();
    }

    /**
     * Text cut down to fit a narrower column than the one it came from.
     *
     * <p>A thousand character description has to become a five hundred
     * character audit detail somewhere. Doing it here, rather than letting
     * each caller improvise, means every trail entry is shortened the same
     * way: whitespace flattened to single spaces so a multi-line note reads
     * as one, then cut with an ellipsis so a reader can see that there was
     * more.</p>
     *
     * <p>Unlike the validators above this returns a value instead of
     * complaining, because the caller is recording history rather than
     * accepting input. History that will not fit is shortened, not
     * refused.</p>
     *
     * @return the shortened text, or null when there was nothing to keep
     */
    public static String shorten(String text, int maxLength) {
        if (maxLength < 1) {
            throw new IllegalArgumentException("A shortened length must be at least 1");
        }
        String trimmed = trimToNull(text);
        if (trimmed == null) {
            return null;
        }
        String flattened = trimmed.replaceAll("\\s+", " ");
        if (flattened.length() <= maxLength) {
            return flattened;
        }
        if (maxLength <= 3) {
            return flattened.substring(0, maxLength);
        }
        return flattened.substring(0, maxLength - 3) + "...";
    }
}
