package com.amdocs.telecom.dao;

import com.amdocs.telecom.exception.DataAccessException;
import com.amdocs.telecom.exception.ErrorCode;
import com.amdocs.telecom.exception.TSATMSException;
import com.amdocs.telecom.util.AppLogger;
import com.amdocs.telecom.util.DBConnection;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;

/**
 * Runs a block of work as one database transaction.
 *
 * <p>Turns autocommit off, binds the connection to the current thread so
 * every DAO the block touches uses it, and then commits once. Any exception
 * at all rolls the whole thing back before it is rethrown, so a half applied
 * change can never be left behind.</p>
 *
 * <p>Calls nest safely. If a transaction is already running on this thread
 * the inner call simply joins it rather than starting a second one, and only
 * the outermost call commits. Without that, a service method that both runs
 * its own transaction and calls another such method would commit half its
 * work early.</p>
 */
public final class TransactionTemplate {

    private TransactionTemplate() {
        throw new AssertionError("TransactionTemplate is not instantiable");
    }

    /**
     * Work that produces a result.
     */
    @FunctionalInterface
    public interface TransactionCallback<T> {
        T doInTransaction(TransactionContext context);
    }

    /**
     * Work that produces nothing.
     */
    @FunctionalInterface
    public interface TransactionWork {
        void doInTransaction(TransactionContext context);
    }

    /**
     * Runs the callback in a transaction and returns its result.
     *
     * @throws DataAccessException if the commit or rollback itself fails
     */
    public static <T> T execute(TransactionCallback<T> callback) {
        if (ConnectionScope.isTransactionActive()) {
            return callback.doInTransaction(new JdbcTransactionContext(ConnectionScope.activeConnection()));
        }
        return executeNew(callback);
    }

    /**
     * Runs the work in a transaction.
     */
    public static void run(TransactionWork work) {
        execute(context -> {
            work.doInTransaction(context);
            return null;
        });
    }

    private static <T> T executeNew(TransactionCallback<T> callback) {
        Connection connection = DBConnection.getInstance().getConnection();
        boolean previousAutoCommit = true;
        boolean committed = false;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            ConnectionScope.bind(connection);

            T result = callback.doInTransaction(new JdbcTransactionContext(connection));

            connection.commit();
            committed = true;
            AppLogger.debug(TransactionTemplate.class, "Transaction committed");
            return result;
        } catch (SQLException cause) {
            DBConnection.rollbackQuietly(connection);
            throw new DataAccessException(ErrorCode.DB_TRANSACTION_FAILED,
                    "Transaction failed and was rolled back: " + cause.getMessage(), cause);
        } catch (TSATMSException cause) {
            DBConnection.rollbackQuietly(connection);
            AppLogger.warn(TransactionTemplate.class,
                    "Transaction rolled back: " + cause.toDisplayString());
            throw cause;
        } catch (RuntimeException cause) {
            DBConnection.rollbackQuietly(connection);
            throw new DataAccessException(ErrorCode.DB_TRANSACTION_FAILED,
                    "Transaction rolled back after an unexpected error: " + cause.getMessage(), cause);
        } finally {
            ConnectionScope.unbind();
            if (!committed) {
                // A commit that threw leaves the transaction open; make sure nothing survives.
                DBConnection.rollbackQuietly(connection);
            }
            DBConnection.restoreAutoCommit(connection, previousAutoCommit);
            DBConnection.closeQuietly(connection);
        }
    }

    /**
     * Savepoint handling for the running transaction.
     */
    private static final class JdbcTransactionContext implements TransactionContext {

        private final Connection connection;

        private JdbcTransactionContext(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Connection connection() {
            return connection;
        }

        @Override
        public Savepoint savepoint(String name) {
            try {
                return connection.setSavepoint(name);
            } catch (SQLException cause) {
                throw new DataAccessException(ErrorCode.DB_TRANSACTION_FAILED,
                        "Could not create savepoint '" + name + "': " + cause.getMessage(), cause);
            }
        }

        @Override
        public void rollbackTo(Savepoint savepoint) {
            try {
                connection.rollback(savepoint);
                AppLogger.warn(TransactionTemplate.class,
                        "Rolled back to savepoint " + describe(savepoint));
            } catch (SQLException cause) {
                throw new DataAccessException(ErrorCode.DB_ROLLBACK_FAILED,
                        "Could not roll back to savepoint: " + cause.getMessage(), cause);
            }
        }

        @Override
        public void release(Savepoint savepoint) {
            try {
                connection.releaseSavepoint(savepoint);
            } catch (SQLException cause) {
                AppLogger.debug(TransactionTemplate.class,
                        "Could not release savepoint: " + cause.getMessage());
            }
        }

        private static String describe(Savepoint savepoint) {
            try {
                return savepoint.getSavepointName();
            } catch (SQLException cause) {
                return "(unnamed)";
            }
        }
    }
}
