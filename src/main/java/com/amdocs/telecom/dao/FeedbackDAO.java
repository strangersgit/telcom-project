package com.amdocs.telecom.dao;

import com.amdocs.telecom.model.Feedback;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Customer ratings of closed tickets.
 */
public interface FeedbackDAO extends GenericDAO<Feedback, Long> {

    /**
     * The single rating for a ticket, if one has been given. The unique
     * constraint guarantees there is never more than one.
     */
    Optional<Feedback> findByTicketId(Long ticketId);

    List<Feedback> findByCustomerId(Long customerId);

    List<Feedback> findByRating(int rating);

    /**
     * Mean rating across everything submitted, empty when there is nothing
     * to average.
     */
    OptionalDouble averageRating();

    /**
     * Mean rating for the tickets one engineer resolved.
     */
    OptionalDouble averageRatingForEngineer(Long engineerId);
}
