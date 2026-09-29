package com.amdocs.telecom.exception;

/**
 * Root of the application exception hierarchy.
 *
 * <p>Unchecked so that service and DAO signatures stay readable; every layer
 * still translates low level failures into a subclass carrying an
 * {@link ErrorCode}, so nothing is ever reported as a bare technical stack
 * trace on the console.</p>
 */
public class TSATMSException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public TSATMSException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public TSATMSException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public TSATMSException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public TSATMSException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getDefaultMessage(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * Single line form suitable for printing to the console.
     */
    public String toDisplayString() {
        return "[" + errorCode.getCode() + "] " + getMessage();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + ": " + toDisplayString();
    }
}
