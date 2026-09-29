package com.amdocs.telecom.model;

import java.time.LocalDateTime;

/**
 * An entity whose table carries {@code created_at} and {@code updated_at}.
 *
 * <p>That applies to the six master tables, which are edited in place. The
 * append only records, such as status history and the audit log, stamp a
 * single event time of their own instead and so extend {@link BaseEntity}
 * directly rather than inheriting a pair of timestamps they would never
 * fill.</p>
 */
public abstract class TimestampedEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    protected TimestampedEntity() {
        super();
    }

    protected TimestampedEntity(Long id) {
        super(id);
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
