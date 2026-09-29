package com.amdocs.telecom.exception;

/**
 * Raised when a login attempt fails: bad credentials, wrong CAPTCHA, bad or
 * expired OTP, or an account that is locked or disabled.
 */
public class AuthenticationException extends TSATMSException {

    private static final long serialVersionUID = 1L;

    public AuthenticationException(ErrorCode errorCode) {
        super(errorCode);
    }

    public AuthenticationException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public AuthenticationException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
