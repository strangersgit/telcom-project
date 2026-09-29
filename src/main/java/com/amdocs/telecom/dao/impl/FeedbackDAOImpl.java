package com.amdocs.telecom.dao.impl;

import com.amdocs.telecom.dao.FeedbackDAO;
import com.amdocs.telecom.dao.RowMapper;
import com.amdocs.telecom.model.Feedback;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * JDBC access to the {@code feedback} table.
 */
public class FeedbackDAOImpl extends AbstractJdbcDAO<Feedback> implements FeedbackDAO {

    private static final String INSERT_SQL =
            "INSERT INTO feedback (ticket_id, customer_id, rating, comments, submitted_date) "
                    + "VALUES (?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE feedback SET rating = ?, comments = ? WHERE feedback_id = ?";

    @Override
    public String tableName() {
        return "feedback";
    }

    @Override
    protected String idColumn() {
        return "feedback_id";
    }

    @Override
    protected String defaultOrderBy() {
        return "submitted_date DESC";
    }

    @Override
    protected RowMapper<Feedback> mapper() {
        return FeedbackDAOImpl::mapRow;
    }

    static Feedback mapRow(ResultSet resultSet) throws SQLException {
        Feedback feedback = new Feedback();
        feedback.setId(resultSet.getLong("feedback_id"));
        feedback.setTicketId(resultSet.getLong("ticket_id"));
        feedback.setCustomerId(resultSet.getLong("customer_id"));
        feedback.setRating(resultSet.getInt("rating"));
        feedback.setComments(resultSet.getString("comments"));
        feedback.setSubmittedDate(JdbcSupport.localDateTime(resultSet, "submitted_date"));
        return feedback;
    }

    @Override
    protected String insertSql() {
        return INSERT_SQL;
    }

    @Override
    protected Object[] insertParameters(Feedback feedback) {
        return new Object[]{
                feedback.getTicketId(),
                feedback.getCustomerId(),
                feedback.getRating(),
                feedback.getComments(),
                feedback.getSubmittedDate() == null
                        ? LocalDateTime.now() : feedback.getSubmittedDate()
        };
    }

    @Override
    protected String updateSql() {
        return UPDATE_SQL;
    }

    @Override
    protected Object[] updateParameters(Feedback feedback) {
        return new Object[]{
                feedback.getRating(),
                feedback.getComments(),
                feedback.getId()
        };
    }

    @Override
    public Optional<Feedback> findByTicketId(Long ticketId) {
        return queryOne("SELECT * FROM feedback WHERE ticket_id = ?", ticketId);
    }

    @Override
    public List<Feedback> findByCustomerId(Long customerId) {
        return query("SELECT * FROM feedback WHERE customer_id = ? "
                        + "ORDER BY submitted_date DESC, feedback_id DESC",
                customerId);
    }

    @Override
    public List<Feedback> findByRating(int rating) {
        return query("SELECT * FROM feedback WHERE rating = ? "
                + "ORDER BY submitted_date DESC, feedback_id DESC", rating);
    }

    @Override
    public OptionalDouble averageRating() {
        return average("SELECT AVG(rating) FROM feedback");
    }

    /**
     * Joins through the ticket, because feedback is given about a ticket
     * rather than about an engineer directly.
     */
    @Override
    public OptionalDouble averageRatingForEngineer(Long engineerId) {
        return average("SELECT AVG(f.rating) FROM feedback f "
                + "INNER JOIN trouble_tickets t ON t.ticket_id = f.ticket_id "
                + "WHERE t.assigned_engineer_id = ?", engineerId);
    }

    /**
     * An average over no rows is SQL NULL rather than zero, and the two mean
     * very different things, so the empty case is preserved.
     */
    private OptionalDouble average(String sql, Object... parameters) {
        Optional<Double> value = queryOne(sql,
                resultSet -> JdbcSupport.nullableDouble(resultSet, 1), parameters);
        return value.isPresent() ? OptionalDouble.of(value.get()) : OptionalDouble.empty();
    }
}
