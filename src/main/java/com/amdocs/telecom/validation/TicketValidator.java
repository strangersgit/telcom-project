package com.amdocs.telecom.validation;

import com.amdocs.telecom.dto.TicketRequest;
import com.amdocs.telecom.model.Feedback;
import com.amdocs.telecom.model.enums.ResolutionCode;

/**
 * Everything that has to be true about the text and choices on a ticket
 * before it reaches the database.
 *
 * <p>The case study gives no field rules, so the limits here come from two
 * places that are not guesswork: the column widths in {@code 01_schema.sql},
 * and which columns that script declares {@code NOT NULL}. Checking them in
 * Java as well as in SQL is not duplication for its own sake. The database
 * would reject a 1200 character description with a truncation error naming a
 * column; a console user needs to be told which field is too long and by how
 * much, and to hear about every problem at once rather than one per
 * attempt.</p>
 *
 * <p>The one limit not taken from the schema is the minimum description
 * length. A one word description cannot be diagnosed, so a ticket carrying
 * one wastes an engineer's time twice: once reading it and once asking what
 * it meant.</p>
 */
public final class TicketValidator {

    private TicketValidator() {
        throw new AssertionError("TicketValidator is not instantiable");
    }

    /** Mirrors {@code trouble_tickets.description VARCHAR(1000) NOT NULL}. */
    public static final int DESCRIPTION_MAX = 1000;

    /** Enough to say what is wrong rather than merely that something is. */
    public static final int DESCRIPTION_MIN = 10;

    /** Mirrors {@code trouble_tickets.root_cause VARCHAR(1000)}. */
    public static final int ROOT_CAUSE_MAX = 1000;

    /** Mirrors {@code trouble_tickets.resolution VARCHAR(1000)}. */
    public static final int RESOLUTION_MAX = 1000;

    /** Mirrors {@code ticket_status_history.remarks VARCHAR(500)}. */
    public static final int REMARKS_MAX = 500;

    /** Mirrors {@code feedback.comments VARCHAR(500)}. */
    public static final int COMMENTS_MAX = 500;

    /**
     * A new ticket, checked as a whole so the raiser hears about every
     * missing field in one go.
     */
    public static void validateRaise(TicketRequest request) {
        if (request == null) {
            ValidationResult.forOperation("The ticket could not be raised")
                    .reject("No ticket details were supplied")
                    .throwIfInvalid();
            return;
        }
        ValidationResult result = ValidationResult.forOperation("The ticket could not be raised");
        Validators.requireIdentifier(result, "Service", request.getServiceId());
        Validators.requireValue(result, "Incident category", request.getCategory());
        Validators.requireText(result, "Description", request.getDescription(),
                DESCRIPTION_MIN, DESCRIPTION_MAX);
        result.throwIfInvalid();
    }

    /**
     * The note attached to a status change. Optional, because not every move
     * needs explaining, but section 5's status history exists to be read so
     * one is always worth having.
     */
    public static void validateRemarks(String remarks) {
        ValidationResult result = ValidationResult.forOperation("The status could not be updated");
        Validators.optionalText(result, "Remarks", remarks, REMARKS_MAX);
        result.throwIfInvalid();
    }

    /**
     * A reason that has to be given, used where an action needs accounting
     * for: cancelling a ticket, reopening one, or changing its priority.
     */
    public static void validateReason(String operation, String label, String reason) {
        ValidationResult result = ValidationResult.forOperation(operation);
        Validators.requireText(result, label, reason, 1, REMARKS_MAX);
        result.throwIfInvalid();
    }

    /**
     * Section 10's separate root cause update, recorded while the engineer
     * is still diagnosing and before there is a fix to describe.
     */
    public static void validateDiagnosis(String rootCause) {
        ValidationResult result =
                ValidationResult.forOperation("The diagnosis could not be recorded");
        Validators.requireText(result, "Root cause", rootCause, 1, ROOT_CAUSE_MAX);
        result.throwIfInvalid();
    }

    /**
     * Section 10 resolution: a code, what was wrong, and what was done about
     * it. All three are required together because the schema's
     * {@code chk_tickets_resolved_complete} will not accept a resolved
     * ticket without a code, and a resolution with no explanation is no use
     * to the next engineer who sees the same fault.
     */
    public static void validateResolution(ResolutionCode code, String rootCause,
                                          String resolution) {
        ValidationResult result = ValidationResult.forOperation("The ticket could not be resolved");
        Validators.requireValue(result, "Resolution code", code);
        Validators.requireText(result, "Root cause", rootCause, 1, ROOT_CAUSE_MAX);
        Validators.requireText(result, "Resolution", resolution, 1, RESOLUTION_MAX);
        result.throwIfInvalid();
    }

    /**
     * Section 13 feedback. The rating bounds are the model's own, which are
     * also the schema's {@code chk_feedback_rating}; the comment is
     * optional, since a star rating on its own is still an answer.
     */
    public static void validateFeedback(int rating, String comments) {
        ValidationResult result = ValidationResult.forOperation("The feedback could not be saved");
        Validators.requireRange(result, "Rating", rating, Feedback.MIN_RATING,
                Feedback.MAX_RATING);
        Validators.optionalText(result, "Comments", comments, COMMENTS_MAX);
        result.throwIfInvalid();
    }
}
