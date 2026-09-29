package com.amdocs.telecom.dao.impl;

import com.amdocs.telecom.dao.GenericDAO;
import com.amdocs.telecom.dao.RowMapper;
import com.amdocs.telecom.exception.DataAccessException;
import com.amdocs.telecom.exception.ErrorCode;
import com.amdocs.telecom.model.BaseEntity;
import com.amdocs.telecom.util.AppLogger;

import java.util.List;
import java.util.Optional;

/**
 * The table shaped half of the DAO layer: the five operations every entity
 * needs, written once.
 *
 * <p>A subclass supplies the table name, the mapper and the two statements
 * that are genuinely specific to it, and inherits the rest from here and
 * from {@link JdbcOperations}.</p>
 *
 * @param <T> entity type, which must carry a Long surrogate key
 */
public abstract class AbstractJdbcDAO<T extends BaseEntity> extends JdbcOperations
        implements GenericDAO<T, Long> {

    /* ---------- Supplied by the subclass ---------- */

    @Override
    public abstract String tableName();

    /**
     * Primary key column, used by the inherited finders.
     */
    protected abstract String idColumn();

    /**
     * Turns a row of this table into an entity.
     */
    protected abstract RowMapper<T> mapper();

    protected abstract String insertSql();

    protected abstract Object[] insertParameters(T entity);

    protected abstract String updateSql();

    protected abstract Object[] updateParameters(T entity);

    /**
     * Ordering for {@link #findAll()}.
     */
    protected String defaultOrderBy() {
        return idColumn();
    }

    @Override
    protected final String sourceName() {
        return tableName();
    }

    /* ---------- GenericDAO ---------- */

    @Override
    public Optional<T> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return queryOne("SELECT * FROM " + tableName() + " WHERE " + idColumn() + " = ?", id);
    }

    @Override
    public List<T> findAll() {
        return query("SELECT * FROM " + tableName() + " ORDER BY " + defaultOrderBy());
    }

    @Override
    public T insert(T entity) {
        long generatedKey = insertReturningKey(insertSql(), insertParameters(entity));
        entity.setId(generatedKey);
        AppLogger.debug(getClass(), "Inserted " + tableName() + " id=" + generatedKey);
        return entity;
    }

    @Override
    public boolean update(T entity) {
        if (entity == null || entity.getId() == null) {
            throw new DataAccessException(ErrorCode.DB_UPDATE_FAILED,
                    "Cannot update an unsaved " + tableName() + " record");
        }
        return executeUpdate(updateSql(), updateParameters(entity)) > 0;
    }

    @Override
    public boolean deleteById(Long id) {
        if (id == null) {
            return false;
        }
        return executeUpdate("DELETE FROM " + tableName() + " WHERE " + idColumn() + " = ?", id) > 0;
    }

    @Override
    public long count() {
        return queryLong("SELECT COUNT(*) FROM " + tableName()).orElse(0L);
    }

    /* ---------- Convenience over JdbcOperations ---------- */

    /**
     * Runs a query and maps every row with this DAO's own mapper, which is
     * what the great majority of finders want.
     */
    protected List<T> query(String sql, Object... parameters) {
        RowMapper<T> rowMapper = mapper();
        return super.query(sql, rowMapper, parameters);
    }

    protected Optional<T> queryOne(String sql, Object... parameters) {
        RowMapper<T> rowMapper = mapper();
        return super.queryOne(sql, rowMapper, parameters);
    }
}
