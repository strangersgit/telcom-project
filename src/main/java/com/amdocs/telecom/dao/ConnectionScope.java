package com.amdocs.telecom.dao;

import com.amdocs.telecom.util.DBConnection;

import java.sql.Connection;

/**
 * Supplies the connection a DAO should use, and closes it only if the DAO
 * was the one that opened it.
 *
 * <p>This is what lets a DAO method work standalone and also take part in a
 * larger transaction without changing its signature. When a transaction is
 * running on this thread, the scope hands back that connection and closing it
 * does nothing, so the transaction survives until the code that started it
 * decides to commit. Outside a transaction the scope opens its own
 * autocommit connection and closes it on the way out.</p>
 *
 * <p>The binding is per thread, which matters once the background workers in
 * section 11 start running: each worker gets its own connection and its own
 * transaction with no interference.</p>
 */
public final class ConnectionScope implements AutoCloseable {

    private static final ThreadLocal<Connection> TRANSACTIONAL = new ThreadLocal<>();

    private final Connection connection;
    private final boolean owned;

    private ConnectionScope(Connection connection, boolean owned) {
        this.connection = connection;
        this.owned = owned;
    }

    /**
     * Joins the transaction running on this thread, or opens a standalone
     * connection when there is none.
     */
    public static ConnectionScope open() {
        Connection active = TRANSACTIONAL.get();
        if (active != null) {
            return new ConnectionScope(active, false);
        }
        return new ConnectionScope(DBConnection.getInstance().getConnection(), true);
    }

    public Connection connection() {
        return connection;
    }

    /**
     * True when this scope joined an existing transaction rather than opening
     * a connection of its own.
     */
    public boolean isJoined() {
        return !owned;
    }

    @Override
    public void close() {
        if (owned) {
            DBConnection.closeQuietly(connection);
        }
    }

    /* ---------- Called by TransactionTemplate only ---------- */

    static boolean isTransactionActive() {
        return TRANSACTIONAL.get() != null;
    }

    static Connection activeConnection() {
        return TRANSACTIONAL.get();
    }

    static void bind(Connection connection) {
        TRANSACTIONAL.set(connection);
    }

    static void unbind() {
        TRANSACTIONAL.remove();
    }
}
