package com.amdocs.telecom.dao.impl;

import com.amdocs.telecom.dao.ConnectionScope;
import com.amdocs.telecom.dao.RowMapper;
import com.amdocs.telecom.exception.DataAccessException;
import com.amdocs.telecom.exception.DuplicateResourceException;
import com.amdocs.telecom.exception.ErrorCode;
import com.amdocs.telecom.util.AppLogger;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The JDBC mechanics every data access class needs: open a scope, prepare a
 * statement, bind, read, close in the right order, and turn
 * {@link SQLException} into the application's own exception type.
 *
 * <p>Separate from {@link AbstractJdbcDAO} because not everything that talks
 * to the database is a table. The report classes read views and call
 * procedures, and they need this plumbing without inheriting insert, update
 * and delete that would make no sense for them.</p>
 *
 * <p>Every statement is a {@link PreparedStatement}. No value is ever
 * concatenated into SQL.</p>
 */
public abstract class JdbcOperations {

    /** MySQL's error number for a unique constraint breach. */
    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    /**
     * What this class reads from, used in log lines and error messages.
     */
    protected abstract String sourceName();

    /* ---------- Queries ---------- */

    /**
     * Runs a query and maps every row.
     */
    protected <R> List<R> query(String sql, RowMapper<R> rowMapper, Object... parameters) {
        try (ConnectionScope scope = ConnectionScope.open();
             PreparedStatement statement = scope.connection().prepareStatement(sql)) {
            JdbcSupport.bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<R> results = new ArrayList<>();
                while (resultSet.next()) {
                    results.add(rowMapper.map(resultSet));
                }
                return results;
            }
        } catch (SQLException cause) {
            throw failure(ErrorCode.DB_QUERY_FAILED, "Query failed", sql, parameters, cause);
        }
    }

    /**
     * Reads at most one row, which is only ever used with a unique key.
     */
    protected <R> Optional<R> queryOne(String sql, RowMapper<R> rowMapper, Object... parameters) {
        try (ConnectionScope scope = ConnectionScope.open();
             PreparedStatement statement = scope.connection().prepareStatement(sql)) {
            JdbcSupport.bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.ofNullable(rowMapper.map(resultSet)) : Optional.empty();
            }
        } catch (SQLException cause) {
            throw failure(ErrorCode.DB_QUERY_FAILED, "Query failed", sql, parameters, cause);
        }
    }

    /**
     * Reads a single numeric value from the first column. Empty when the
     * query returns no row, or a SQL NULL because an aggregate had nothing
     * to work on: zero and "nothing to average" are not the same answer.
     */
    protected Optional<Long> queryLong(String sql, Object... parameters) {
        return queryOne(sql, resultSet -> JdbcSupport.nullableLong(resultSet, 1), parameters);
    }

    /* ---------- Writes ---------- */

    /**
     * Runs an insert, update or delete and reports how many rows changed.
     */
    protected int executeUpdate(String sql, Object... parameters) {
        try (ConnectionScope scope = ConnectionScope.open();
             PreparedStatement statement = scope.connection().prepareStatement(sql)) {
            JdbcSupport.bind(statement, parameters);
            return statement.executeUpdate();
        } catch (SQLException cause) {
            throw failure(ErrorCode.DB_UPDATE_FAILED, "Update failed", sql, parameters, cause);
        }
    }

    /**
     * Runs an insert and returns the key the database generated.
     */
    protected long insertReturningKey(String sql, Object... parameters) {
        try (ConnectionScope scope = ConnectionScope.open();
             PreparedStatement statement = scope.connection()
                     .prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            JdbcSupport.bind(statement, parameters);
            if (statement.executeUpdate() == 0) {
                throw new DataAccessException(ErrorCode.DB_UPDATE_FAILED,
                        "Insert into " + sourceName() + " affected no rows");
            }
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new DataAccessException(ErrorCode.DB_UPDATE_FAILED,
                        "Insert into " + sourceName() + " returned no generated key");
            }
        } catch (SQLException cause) {
            throw failure(ErrorCode.DB_UPDATE_FAILED, "Insert failed", sql, parameters, cause);
        }
    }

    /**
     * Writes many rows with one statement and one round trip, which is what
     * the event simulator and the SLA monitor need when they produce a burst
     * of rows at once.
     *
     * @return the number of rows each statement in the batch affected
     */
    protected int[] executeBatch(String sql, List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return new int[0];
        }
        try (ConnectionScope scope = ConnectionScope.open();
             PreparedStatement statement = scope.connection().prepareStatement(sql)) {
            for (Object[] row : rows) {
                JdbcSupport.bind(statement, row);
                statement.addBatch();
            }
            int[] results = statement.executeBatch();
            AppLogger.debug(getClass(), "Batched " + results.length + " rows into " + sourceName());
            return results;
        } catch (SQLException cause) {
            throw failure(ErrorCode.DB_BATCH_FAILED, "Batch failed", sql, null, cause);
        }
    }

    /**
     * Counts the rows a batch actually wrote. A driver is allowed to report
     * success without a count, so that case is treated as written rather
     * than as a failure.
     */
    protected static int countBatchWrites(int[] results) {
        int written = 0;
        for (int result : results) {
            if (result > 0 || result == Statement.SUCCESS_NO_INFO) {
                written++;
            }
        }
        return written;
    }

    /* ---------- Error translation ---------- */

    /**
     * Turns a JDBC failure into the application's own exception, picking out
     * the one case a caller can act on: a unique key that already exists.
     *
     * <p>A duplicate is an ordinary outcome of a user choosing a taken
     * username or ticket reference, so it is logged as a warning without a
     * stack trace. Everything else is a fault and keeps its trace.</p>
     */
    protected RuntimeException failure(ErrorCode errorCode, String what, String sql,
                                       Object[] parameters, SQLException cause) {
        String description = what + " on " + sourceName() + ": " + cause.getMessage()
                + " [sql=" + JdbcSupport.abbreviate(sql) + "]";

        if (cause.getErrorCode() == MYSQL_DUPLICATE_ENTRY) {
            AppLogger.warn(getClass(),
                    sourceName() + " rejected a duplicate value: " + cause.getMessage());
            return new DuplicateResourceException(
                    sourceName() + " rejected a duplicate value: " + cause.getMessage());
        }

        AppLogger.error(getClass(), description
                + (parameters == null ? "" : " params=" + Arrays.toString(parameters)), cause);
        return new DataAccessException(errorCode, description, cause);
    }
}
