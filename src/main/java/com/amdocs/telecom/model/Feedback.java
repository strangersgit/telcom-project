package com.amdocs.telecom.model;

import java.time.LocalDateTime;

/**
 * A customer's rating of how a closed ticket was handled.
 *
 * <p>One rating per ticket, enforced by a unique constraint rather than by
 * application logic alone.</p>
 */
public class Feedback extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** The database enforces the same bounds with a check constraint. */
    public static final int MIN_RATING = 1;
    public static final int MAX_RATING = 5;

    private Long ticketId;
    private Long customerId;
    private int rating;
    private String comments;
    private LocalDateTime submittedDate;

    public Feedback() {
        super();
    }

    public Feedback(Long ticketId, Long customerId, int rating, String comments) {
        this.ticketId = ticketId;
        this.customerId = customerId;
        this.rating = rating;
        this.comments = comments;
        this.submittedDate = stampNow();
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public int getRating() {
        return rating;
    }

    public void setRating(int rating) {
        this.rating = rating;
    }

    public String getComments() {
        return comments;
    }

    public void setComments(String comments) {
        this.comments = comments;
    }

    public LocalDateTime getSubmittedDate() {
        return submittedDate;
    }

    public void setSubmittedDate(LocalDateTime submittedDate) {
        this.submittedDate = submittedDate;
    }

    public boolean isPositive() {
        return rating >= 4;
    }

    public boolean isNegative() {
        return rating <= 2;
    }

    /**
     * Rating drawn with plain ASCII, so it renders on a Windows console
     * whatever code page is active.
     */
    public String toStars() {
        StringBuilder stars = new StringBuilder(MAX_RATING);
        for (int position = 1; position <= MAX_RATING; position++) {
            stars.append(position <= rating ? '*' : '.');
        }
        return stars.toString();
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-7s %d/5  %-18s %s",
                toStars(),
                rating,
                Displayable.formatDateTime(submittedDate),
                Displayable.truncate(comments, 50));
    }
}
