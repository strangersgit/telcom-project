/**
 * Domain entities that mirror the database tables.
 *
 * <p>Every field is private with accessors, giving the encapsulation the case
 * study asks for. The hierarchy runs {@code BaseEntity} for identity,
 * {@code TimestampedEntity} for the six master tables that carry created and
 * updated stamps, and {@code AbstractParty} for the three kinds of person the
 * system knows about. The append only records extend the base directly, since
 * they stamp a single event time of their own.</p>
 *
 * <p>Three interfaces cut across that hierarchy: {@code Identifiable} is
 * generic in the key type so the DAO layer can be written once,
 * {@code Displayable} supplies console rendering through default and static
 * methods, and {@code Auditable} lets the audit trail describe an entity
 * without knowing its concrete type.</p>
 */
package com.amdocs.telecom.model;
