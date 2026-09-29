package com.amdocs.telecom.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Root of the entity hierarchy.
 *
 * <p>Carries the surrogate key and the identity rules that go with it, and
 * nothing else. Subclasses are obliged to supply their own console summary,
 * which is what makes a mixed list of entities printable without any
 * instanceof checks.</p>
 */
public abstract class BaseEntity implements Identifiable<Long>, Displayable, Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    protected BaseEntity() {
        // Populated by the DAO layer when reading, or by a service when creating.
    }

    protected BaseEntity(Long id) {
        this.id = id;
    }

    @Override
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    /**
     * Two persisted entities of the same type are the same record when their
     * keys match. Unsaved entities fall back to reference identity, because
     * two different new objects are not the same record just because neither
     * has been written yet.
     */
    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        BaseEntity that = (BaseEntity) other;
        return id != null && id.equals(that.id);
    }

    @Override
    public final int hashCode() {
        return id == null ? System.identityHashCode(this) : id.hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" + toSummaryLine() + "}";
    }

    /**
     * The moment to stamp on a record being created, to whole seconds.
     *
     * <p>Every timestamp in this system is stored in a DATETIME column, which
     * holds no fractional part. MySQL does not discard the fraction it is
     * given, it rounds: half a second past becomes the next second. A row
     * written at 10:00:00.7 therefore lands as 10:00:01, later than a row the
     * database itself stamps at 10:00:00.9 with NOW(), which truncates. That
     * is enough to put an audit trail into the wrong order.</p>
     *
     * <p>Dropping the fraction here means the value written is exactly the
     * value the object holds, and rows written in sequence read back in
     * sequence.</p>
     */
    protected static LocalDateTime stampNow() {
        return LocalDateTime.now().withNano(0);
    }
}
