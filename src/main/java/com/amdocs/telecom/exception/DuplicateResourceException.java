package com.amdocs.telecom.exception;

/**
 * Raised when an insert would breach a unique constraint, for example a
 * duplicate customer number or employee code.
 */
public class DuplicateResourceException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public DuplicateResourceException(String message) {
        super(ErrorCode.DUPLICATE_RESOURCE, message);
    }

    public DuplicateResourceException(String resourceType, Object identifier) {
        super(ErrorCode.DUPLICATE_RESOURCE,
                resourceType + " already exists with identifier '" + identifier + "'");
    }
}
