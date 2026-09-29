package com.amdocs.telecom.exception;

/**
 * Raised when application configuration is missing, unreadable or invalid.
 */
public class ConfigurationException extends TSATMSException {

    private static final long serialVersionUID = 1L;

    public ConfigurationException(ErrorCode errorCode) {
        super(errorCode);
    }

    public ConfigurationException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public ConfigurationException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
