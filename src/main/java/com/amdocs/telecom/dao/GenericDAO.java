package com.amdocs.telecom.dao;

import com.amdocs.telecom.exception.ResourceNotFoundException;
import com.amdocs.telecom.model.Identifiable;

import java.util.List;
import java.util.Optional;

/**
 * The operations every table needs, written once against the key type.
 *
 * <p>Bounding the type parameter on {@link Identifiable} is what makes the
 * shared implementation possible: the base class can read an entity's key
 * and set a generated one without knowing which table it belongs to.</p>
 *
 * <p>Lookups return {@link Optional} rather than null so a caller cannot
 * forget the missing case. The {@code getById} default is there for the
 * callers that genuinely cannot continue without the record, turning absence
 * into an exception at the point it is noticed rather than a null that fails
 * somewhere further on.</p>
 *
 * @param <T>  entity type
 * @param <ID> primary key type
 */
public interface GenericDAO<T extends Identifiable<ID>, ID> {

    Optional<T> findById(ID id);

    List<T> findAll();

    /**
     * Writes a new row and returns the entity with its generated key set.
     */
    T insert(T entity);

    /**
     * @return true when a row was actually changed
     */
    boolean update(T entity);

    boolean deleteById(ID id);

    long count();

    /**
     * Name of the table, used in error messages.
     */
    String tableName();

    default boolean existsById(ID id) {
        return findById(id).isPresent();
    }

    /**
     * Like {@link #findById} but insists the record exists.
     *
     * @throws ResourceNotFoundException when nothing matches
     */
    default T getById(ID id) {
        return findById(id).orElseThrow(() -> new ResourceNotFoundException(tableName(), id));
    }

    /**
     * Inserts or updates depending on whether the entity has been saved
     * before.
     */
    default T save(T entity) {
        if (entity.isPersisted()) {
            update(entity);
            return entity;
        }
        return insert(entity);
    }
}
