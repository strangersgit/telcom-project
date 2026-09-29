package com.amdocs.telecom.dao;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Turns the current row of a result set into an object.
 *
 * <p>A functional interface, so every mapping in the layer below is written
 * as a method reference and handed to the shared query helpers. That is what
 * keeps the {@code try}, {@code close} and exception translation in one place
 * instead of repeated in every finder.</p>
 *
 * @param <T> type produced from a row
 */
@FunctionalInterface
public interface RowMapper<T> {

    /**
     * Reads the row the result set is currently positioned on. Implementations
     * must not call {@code next()}.
     */
    T map(ResultSet resultSet) throws SQLException;
}
