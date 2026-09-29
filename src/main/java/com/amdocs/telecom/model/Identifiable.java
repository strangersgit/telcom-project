package com.amdocs.telecom.model;

/**
 * Anything carrying a surrogate primary key.
 *
 * <p>Generic in the key type so the DAO layer can be written once against
 * {@code GenericDAO<T extends Identifiable<ID>, ID>} rather than repeated per
 * entity.</p>
 *
 * @param <ID> type of the primary key
 */
public interface Identifiable<ID> {

    ID getId();

    /**
     * False for an object that has been built in memory but never written, so
     * services can tell an insert from an update without asking the database.
     */
    default boolean isPersisted() {
        return getId() != null;
    }
}
