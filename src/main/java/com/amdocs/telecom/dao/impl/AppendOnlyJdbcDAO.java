package com.amdocs.telecom.dao.impl;

import com.amdocs.telecom.exception.DataAccessException;
import com.amdocs.telecom.exception.ErrorCode;
import com.amdocs.telecom.model.BaseEntity;

/**
 * Base for the tables that only ever grow: status history, escalation
 * history and the audit log.
 *
 * <p>A trail that can be edited or pruned is not a trail, so update and
 * delete are refused here rather than left available and merely discouraged.
 * The refusal is loud, because reaching it means a caller has misunderstood
 * what the table is for.</p>
 *
 * @param <T> entity type
 */
public abstract class AppendOnlyJdbcDAO<T extends BaseEntity> extends AbstractJdbcDAO<T> {

    @Override
    public final boolean update(T entity) {
        throw refuse("update");
    }

    @Override
    public final boolean deleteById(Long id) {
        throw refuse("delete from");
    }

    @Override
    protected final String updateSql() {
        throw refuse("update");
    }

    @Override
    protected final Object[] updateParameters(T entity) {
        throw refuse("update");
    }

    private DataAccessException refuse(String operation) {
        return new DataAccessException(ErrorCode.DB_UPDATE_FAILED,
                "Cannot " + operation + " " + tableName()
                        + ": it is an append only trail and history must not be rewritten");
    }
}
