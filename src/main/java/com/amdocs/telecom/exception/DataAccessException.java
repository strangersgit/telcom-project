package com.amdocs.telecom.exception;

/**
 * Wraps every {@link java.sql.SQLException} escaping the DAO layer so that
 * services and controllers never depend on JDBC types.
 */
public class DataAccessException extends TSATMSException {

    private static final long serialVersionUID = 1L;

    public DataAccessException(ErrorCode errorCode) {
        super(errorCode);
    }

    public DataAccessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public DataAccessException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    public DataAccessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
