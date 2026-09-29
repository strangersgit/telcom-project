package com.amdocs.telecom.exception;

/**
 * Raised when a lookup by identifier finds nothing.
 */
public class ResourceNotFoundException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String message) {
        super(ErrorCode.RESOURCE_NOT_FOUND, message);
    }

    public ResourceNotFoundException(String resourceType, Object identifier) {
        super(ErrorCode.RESOURCE_NOT_FOUND,
                resourceType + " not found for identifier '" + identifier + "'");
    }
}
