package com.amdocs.telecom.dao.impl;

import com.amdocs.telecom.exception.DataAccessException;
import com.amdocs.telecom.exception.ErrorCode;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Translation between JDBC types and the types the model actually uses.
 *
 * <p>Two things here are easy to get wrong and are therefore done in one
 * place. Java 8 date types are not JDBC types, so every bind and every read
 * has to convert. And the primitive getters on a result set return zero for
 * a SQL NULL rather than telling you, which would silently turn an
 * unassigned engineer into engineer zero; the nullable readers below check
 * {@code wasNull} and hand back a real null instead.</p>
 */
final class JdbcSupport {

    private JdbcSupport() {
        throw new AssertionError("JdbcSupport is not instantiable");
    }

    /* ---------- Binding ---------- */

    /**
     * Binds positional parameters, converting model types on the way in.
     * Enums are stored by name, which is what the check constraints compare
     * against.
     */
    static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        if (parameters == null) {
            return;
        }
        for (int index = 0; index < parameters.length; index++) {
            bindOne(statement, index + 1, parameters[index]);
        }
    }

    private static void bindOne(PreparedStatement statement, int position, Object value)
            throws SQLException {
        if (value == null) {
            statement.setNull(position, Types.NULL);
        } else if (value instanceof String) {
            statement.setString(position, (String) value);
        } else if (value instanceof Integer) {
            statement.setInt(position, (Integer) value);
        } else if (value instanceof Long) {
            statement.setLong(position, (Long) value);
        } else if (value instanceof Boolean) {
            statement.setBoolean(position, (Boolean) value);
        } else if (value instanceof Double) {
            statement.setDouble(position, (Double) value);
        } else if (value instanceof LocalDateTime) {
            // Seconds only. Every DATETIME column here is declared without a
            // fractional part, and MySQL rounds rather than truncates what it
            // is given: a value half a second past the second is stored as
            // the next one. Two rows written in order can then come back out
            // of order, and a value read back does not equal the one written.
            // Dropping the fraction on the way in is the one place that
            // cannot be forgotten.
            statement.setTimestamp(position,
                    Timestamp.valueOf(((LocalDateTime) value).withNano(0)));
        } else if (value instanceof LocalDate) {
            statement.setDate(position, Date.valueOf((LocalDate) value));
        } else if (value instanceof Enum<?>) {
            statement.setString(position, ((Enum<?>) value).name());
        } else {
            statement.setObject(position, value);
        }
    }

    /* ---------- Reading ---------- */

    static LocalDateTime localDateTime(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    static LocalDate localDate(ResultSet resultSet, String column) throws SQLException {
        Date date = resultSet.getDate(column);
        return date == null ? null : date.toLocalDate();
    }

    static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    static Double nullableDouble(ResultSet resultSet, String column) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }

    /**
     * Index based variants, for the aggregate queries whose single column
     * has no useful name.
     */
    static Double nullableDouble(ResultSet resultSet, int index) throws SQLException {
        double value = resultSet.getDouble(index);
        return resultSet.wasNull() ? null : value;
    }

    static Long nullableLong(ResultSet resultSet, int index) throws SQLException {
        long value = resultSet.getLong(index);
        return resultSet.wasNull() ? null : value;
    }

    /**
     * Reads an enum stored by name. A value the enum does not recognise is
     * reported rather than swallowed, because it means the database and the
     * code have drifted apart.
     */
    static <E extends Enum<E>> E enumValue(ResultSet resultSet, String column, Class<E> type)
            throws SQLException {
        String name = resultSet.getString(column);
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        try {
            return Enum.valueOf(type, name.trim());
        } catch (IllegalArgumentException cause) {
            throw new DataAccessException(ErrorCode.DB_QUERY_FAILED,
                    "Column '" + column + "' holds '" + name + "', which is not a valid "
                            + type.getSimpleName(), cause);
        }
    }

    /**
     * True when the result set actually has the named column, used by mappers
     * shared between a table and a view that exposes a subset of it.
     */
    static boolean hasColumn(ResultSet resultSet, String column) throws SQLException {
        try {
            resultSet.findColumn(column);
            return true;
        } catch (SQLException missing) {
            return false;
        }
    }

    /**
     * Shortens a statement for a log or error message so a long insert does
     * not fill the console.
     */
    static String abbreviate(String sql) {
        if (sql == null) {
            return "(none)";
        }
        String flattened = sql.replaceAll("\\s+", " ").trim();
        return flattened.length() <= 140 ? flattened : flattened.substring(0, 137) + "...";
    }
}
