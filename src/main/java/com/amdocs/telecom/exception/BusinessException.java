package com.amdocs.telecom.exception;

/**
 * Raised when an operation is technically valid but breaks a domain rule, such
 * as an illegal ticket status transition or an escalation past the top level.
 */
public class BusinessException extends TSATMSException {

    private static final long serialVersionUID = 1L;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
