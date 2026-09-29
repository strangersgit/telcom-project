package com.amdocs.telecom.exception;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Raised when user supplied input fails validation.
 *
 * <p>Carries every individual field failure rather than only the first, so the
 * console can show a customer all of their mistakes in one pass.</p>
 */
public class ValidationException extends TSATMSException {

    private static final long serialVersionUID = 1L;

    private final List<String> fieldErrors;

    public ValidationException(String message) {
        super(ErrorCode.VALIDATION_FAILED, message);
        this.fieldErrors = Collections.emptyList();
    }

    public ValidationException(ErrorCode errorCode, String message) {
        super(errorCode, message);
        this.fieldErrors = Collections.emptyList();
    }

    public ValidationException(String message, List<String> fieldErrors) {
        super(ErrorCode.VALIDATION_FAILED, message);
        this.fieldErrors = fieldErrors == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(fieldErrors));
    }

    public List<String> getFieldErrors() {
        return fieldErrors;
    }

    public boolean hasFieldErrors() {
        return !fieldErrors.isEmpty();
    }

    @Override
    public String toDisplayString() {
        StringBuilder builder = new StringBuilder(super.toDisplayString());
        for (String fieldError : fieldErrors) {
            builder.append(System.lineSeparator()).append("  - ").append(fieldError);
        }
        return builder.toString();
    }
}
