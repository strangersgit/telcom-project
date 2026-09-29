package com.amdocs.telecom.dao;

import java.sql.Connection;
import java.sql.Savepoint;

/**
 * Handed to the body of a transaction so it can set and roll back to
 * savepoints.
 *
 * <p>Savepoints matter for the assignment flow in section 19: the optional
 * steps at the end, raising a notification and writing an audit row, must not
 * be able to undo the assignment itself. Marking a savepoint before them
 * means a failure there rolls back only that part.</p>
 */
public interface TransactionContext {

    /**
     * The connection this transaction is running on, for the rare case where
     * a caller needs raw JDBC.
     */
    Connection connection();

    /**
     * Marks a point the transaction can later be rewound to.
     */
    Savepoint savepoint(String name);

    /**
     * Discards everything done since the savepoint, keeping the work before
     * it and leaving the transaction open.
     */
    void rollbackTo(Savepoint savepoint);

    /**
     * Releases a savepoint that is no longer needed.
     */
    void release(Savepoint savepoint);
}
