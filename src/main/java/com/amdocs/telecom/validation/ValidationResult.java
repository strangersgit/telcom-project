package com.amdocs.telecom.validation;

import com.amdocs.telecom.exception.ValidationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects everything wrong with one piece of input before complaining.
 *
 * <p>Checking a field and throwing immediately means a customer filling in a
 * ticket is told about one mistake, fixes it, and is then told about the
 * next. Gathering the problems first and raising a single
 * {@link ValidationException} carrying all of them lets the console show the
 * whole list in one pass, which is why that exception keeps a list of field
 * errors rather than just a message.</p>
 *
 * <p>Not thread safe, and does not need to be: an instance lives inside one
 * validation call.</p>
 */
public final class ValidationResult {

    private final String operation;
    private final List<String> problems = new ArrayList<String>();

    private ValidationResult(String operation) {
        this.operation = operation;
    }

    /**
     * @param operation named in the summary message, for example
     *                  "The ticket could not be raised"
     */
    public static ValidationResult forOperation(String operation) {
        return new ValidationResult(operation);
    }

    public ValidationResult reject(String problem) {
        if (problem != null && !problem.trim().isEmpty()) {
            problems.add(problem.trim());
        }
        return this;
    }

    public ValidationResult rejectIf(boolean unacceptable, String problem) {
        if (unacceptable) {
            reject(problem);
        }
        return this;
    }

    public boolean isValid() {
        return problems.isEmpty();
    }

    public List<String> getProblems() {
        return Collections.unmodifiableList(problems);
    }

    /**
     * Raises the accumulated problems, or returns quietly when there are
     * none.
     */
    public void throwIfInvalid() {
        if (!problems.isEmpty()) {
            throw new ValidationException(operation, problems);
        }
    }

    @Override
    public String toString() {
        return isValid() ? operation + ": valid"
                : operation + ": " + problems.size() + " problem(s) " + problems;
    }
}
