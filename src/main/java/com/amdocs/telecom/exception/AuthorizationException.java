package com.amdocs.telecom.exception;

/**
 * Raised when an authenticated user attempts an operation their role does not
 * permit.
 */
public class AuthorizationException extends TSATMSException {

    private static final long serialVersionUID = 1L;

    public AuthorizationException(String message) {
        super(ErrorCode.AUTHZ_ACCESS_DENIED, message);
    }

    public AuthorizationException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
