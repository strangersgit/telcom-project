package com.amdocs.telecom.exception;

/**
 * Catalogue of every error condition the application can report.
 *
 * <p>Codes are stable identifiers safe to print on the console, write to the
 * audit log and quote in a support conversation.</p>
 */
public enum ErrorCode {

    /* ---------- Configuration ---------- */
    CONFIG_NOT_FOUND("CFG-001", "Configuration file could not be located"),
    CONFIG_LOAD_FAILED("CFG-002", "Configuration file could not be read"),
    CONFIG_MISSING_PROPERTY("CFG-003", "Required configuration property is missing"),
    CONFIG_INVALID_VALUE("CFG-004", "Configuration property holds an invalid value"),

    /* ---------- Data access ---------- */
    DB_DRIVER_NOT_FOUND("DAO-001", "JDBC driver could not be loaded"),
    DB_CONNECTION_FAILED("DAO-002", "Unable to establish a database connection"),
    DB_SCHEMA_MISSING("DAO-003", "Application schema does not exist"),
    DB_QUERY_FAILED("DAO-004", "Database query execution failed"),
    DB_UPDATE_FAILED("DAO-005", "Database update failed"),
    DB_TRANSACTION_FAILED("DAO-006", "Database transaction could not be completed"),
    DB_ROLLBACK_FAILED("DAO-007", "Database transaction rollback failed"),
    DB_BATCH_FAILED("DAO-008", "Database batch execution failed"),

    /* ---------- Validation ---------- */
    VALIDATION_FAILED("VAL-001", "Input validation failed"),
    VALIDATION_REQUIRED_FIELD("VAL-002", "A mandatory field was left blank"),
    VALIDATION_INVALID_FORMAT("VAL-003", "Value does not match the expected format"),
    VALIDATION_OUT_OF_RANGE("VAL-004", "Value falls outside the permitted range"),

    /* ---------- Authentication ---------- */
    AUTH_INVALID_CREDENTIALS("AUT-001", "Username or password is incorrect"),
    AUTH_ACCOUNT_LOCKED("AUT-002", "Account is locked after repeated failed attempts"),
    AUTH_ACCOUNT_DISABLED("AUT-003", "Account has been disabled"),
    AUTH_CAPTCHA_MISMATCH("AUT-004", "CAPTCHA response did not match"),
    AUTH_OTP_INVALID("AUT-005", "One time password is incorrect"),
    AUTH_OTP_EXPIRED("AUT-006", "One time password has expired"),
    AUTH_SESSION_EXPIRED("AUT-007", "Session is no longer valid"),

    /* ---------- Authorization ---------- */
    AUTHZ_ACCESS_DENIED("AUZ-001", "Role is not permitted to perform this operation"),

    /* ---------- Business rules ---------- */
    BUSINESS_RULE_VIOLATION("BUS-001", "Operation violates a business rule"),
    RESOURCE_NOT_FOUND("BUS-002", "Requested record does not exist"),
    DUPLICATE_RESOURCE("BUS-003", "A record with the same unique key already exists"),
    INVALID_STATUS_TRANSITION("BUS-004", "Ticket cannot move to the requested status"),
    NO_ENGINEER_AVAILABLE("BUS-005", "No engineer matches the required skill and region"),
    ENGINEER_UNAVAILABLE("BUS-006", "Selected engineer is not currently available"),
    SLA_CONFIG_MISSING("BUS-007", "No SLA configuration defined for this priority"),
    ESCALATION_AT_TOP_LEVEL("BUS-008", "Ticket is already at the highest escalation level"),

    /* ---------- Reporting and files ---------- */
    REPORT_GENERATION_FAILED("RPT-001", "Report could not be generated"),
    FILE_WRITE_FAILED("RPT-002", "Output file could not be written"),

    /* ---------- Concurrency ---------- */
    THREAD_INTERRUPTED("THR-001", "Background worker was interrupted"),
    EVENT_QUEUE_FULL("THR-002", "Network event queue is full"),

    /* ---------- Fallback ---------- */
    UNEXPECTED_ERROR("GEN-001", "An unexpected error occurred");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    @Override
    public String toString() {
        return code + " - " + defaultMessage;
    }
}
