-- =====================================================================
-- TSATMS - 01 - Schema definition
-- ---------------------------------------------------------------------
-- Thirteen normalised tables in third normal form.
--
-- Conventions used throughout:
--   * surrogate BIGINT UNSIGNED primary keys, plus a human readable
--     business key carrying a UNIQUE constraint
--   * enumerated columns stored as the Java constant name and guarded by
--     a CHECK constraint, so the database rejects anything the domain
--     model cannot represent
--   * every table carries created_at and updated_at audit timestamps
--   * indexes on foreign keys, status columns and the date columns the
--     reports filter and group by
-- =====================================================================

USE `${db.schema}`;

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS login_history;
DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS feedback;
DROP TABLE IF EXISTS notifications;
DROP TABLE IF EXISTS network_events;
DROP TABLE IF EXISTS escalation_history;
DROP TABLE IF EXISTS ticket_status_history;
DROP TABLE IF EXISTS trouble_tickets;
DROP TABLE IF EXISTS sla_configuration;
DROP TABLE IF EXISTS network_engineers;
DROP TABLE IF EXISTS telecom_services;
DROP TABLE IF EXISTS customers;
DROP TABLE IF EXISTS users;

SET FOREIGN_KEY_CHECKS = 1;


-- ---------------------------------------------------------------------
-- users
-- Login accounts for all four roles. Customers and engineers link to a
-- row here; service desk administrators and network managers exist as a
-- user row alone.
-- ---------------------------------------------------------------------
CREATE TABLE users (
    user_id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username              VARCHAR(50)     NOT NULL,
    password_hash         VARCHAR(128)    NOT NULL,
    password_salt         VARCHAR(64)     NOT NULL,
    full_name             VARCHAR(100)    NOT NULL,
    email                 VARCHAR(120)    NOT NULL,
    role                  VARCHAR(20)     NOT NULL,
    account_status        VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    failed_login_attempts INT             NOT NULL DEFAULT 0,
    locked_until          DATETIME        NULL,
    last_login_date       DATETIME        NULL,
    must_change_password  BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                          ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT pk_users PRIMARY KEY (user_id),
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_role CHECK (role IN
        ('CUSTOMER', 'SERVICE_DESK', 'NETWORK_ENGINEER', 'NETWORK_MANAGER')),
    CONSTRAINT chk_users_status CHECK (account_status IN
        ('ACTIVE', 'LOCKED', 'DISABLED', 'PENDING_ACTIVATION')),
    CONSTRAINT chk_users_attempts CHECK (failed_login_attempts >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_users_role ON users (role);
CREATE INDEX idx_users_status ON users (account_status);


-- ---------------------------------------------------------------------
-- customers
-- ---------------------------------------------------------------------
CREATE TABLE customers (
    customer_id     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    customer_number VARCHAR(20)     NOT NULL,
    customer_name   VARCHAR(100)    NOT NULL,
    email           VARCHAR(120)    NOT NULL,
    mobile_number   VARCHAR(15)     NOT NULL,
    customer_type   VARCHAR(20)     NOT NULL,
    city            VARCHAR(60)     NOT NULL,
    region          VARCHAR(20)     NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    user_id         BIGINT UNSIGNED NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                    ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT pk_customers PRIMARY KEY (customer_id),
    CONSTRAINT uq_customers_number UNIQUE (customer_number),
    CONSTRAINT uq_customers_email UNIQUE (email),
    CONSTRAINT uq_customers_user UNIQUE (user_id),
    CONSTRAINT fk_customers_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT chk_customers_type CHECK (customer_type IN
        ('CONSUMER', 'SME', 'ENTERPRISE')),
    CONSTRAINT chk_customers_status CHECK (status IN
        ('ACTIVE', 'INACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT chk_customers_region CHECK (region IN
        ('NORTH', 'SOUTH', 'EAST', 'WEST', 'CENTRAL'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_customers_type ON customers (customer_type);
CREATE INDEX idx_customers_status ON customers (status);
CREATE INDEX idx_customers_region ON customers (region);
CREATE INDEX idx_customers_city ON customers (city);


-- ---------------------------------------------------------------------
-- telecom_services
-- ---------------------------------------------------------------------
CREATE TABLE telecom_services (
    service_id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    service_code    VARCHAR(20)     NOT NULL,
    service_name    VARCHAR(100)    NOT NULL,
    service_type    VARCHAR(30)     NOT NULL,
    customer_id     BIGINT UNSIGNED NOT NULL,
    activation_date DATE            NOT NULL,
    service_status  VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                    ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT pk_services PRIMARY KEY (service_id),
    CONSTRAINT uq_services_code UNIQUE (service_code),
    CONSTRAINT fk_services_customer FOREIGN KEY (customer_id)
        REFERENCES customers (customer_id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT chk_services_type CHECK (service_type IN
        ('MOBILE', 'BROADBAND', 'ENTERPRISE_CONNECTIVITY', 'VPN', 'CLOUD_CONNECTIVITY')),
    CONSTRAINT chk_services_status CHECK (service_status IN
        ('PENDING_ACTIVATION', 'ACTIVE', 'SUSPENDED', 'TERMINATED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_services_customer ON telecom_services (customer_id);
CREATE INDEX idx_services_type ON telecom_services (service_type);
CREATE INDEX idx_services_status ON telecom_services (service_status);


-- ---------------------------------------------------------------------
-- network_engineers
-- active_ticket_count is a maintained aggregate. It is kept in step by
-- the assignment transaction so the recommendation query stays a single
-- indexed read rather than a join and count over every open ticket.
-- ---------------------------------------------------------------------
CREATE TABLE network_engineers (
    engineer_id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    employee_code       VARCHAR(20)     NOT NULL,
    engineer_name       VARCHAR(100)    NOT NULL,
    email               VARCHAR(120)    NOT NULL,
    mobile_number       VARCHAR(15)     NOT NULL,
    specialization      VARCHAR(30)     NOT NULL,
    region              VARCHAR(20)     NOT NULL,
    experience_years    INT             NOT NULL DEFAULT 0,
    availability        VARCHAR(20)     NOT NULL DEFAULT 'AVAILABLE',
    active_ticket_count INT             NOT NULL DEFAULT 0,
    max_ticket_capacity INT             NOT NULL DEFAULT 10,
    user_id             BIGINT UNSIGNED NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT pk_engineers PRIMARY KEY (engineer_id),
    CONSTRAINT uq_engineers_code UNIQUE (employee_code),
    CONSTRAINT uq_engineers_email UNIQUE (email),
    CONSTRAINT uq_engineers_user UNIQUE (user_id),
    CONSTRAINT fk_engineers_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT chk_engineers_spec CHECK (specialization IN
        ('CORE_NETWORK', 'RAN', 'BROADBAND', 'IP_NETWORK', 'TRANSMISSION', 'ENTERPRISE_SERVICES')),
    CONSTRAINT chk_engineers_region CHECK (region IN
        ('NORTH', 'SOUTH', 'EAST', 'WEST', 'CENTRAL')),
    CONSTRAINT chk_engineers_availability CHECK (availability IN
        ('AVAILABLE', 'BUSY', 'ON_LEAVE', 'OFF_SHIFT')),
    CONSTRAINT chk_engineers_experience CHECK (experience_years BETWEEN 0 AND 50),
    CONSTRAINT chk_engineers_workload CHECK (active_ticket_count >= 0),
    CONSTRAINT chk_engineers_capacity CHECK (max_ticket_capacity > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Composite index matching the assignment engine's filter: skill, then
-- region, then availability.
CREATE INDEX idx_engineers_assignment
    ON network_engineers (specialization, region, availability, active_ticket_count);
CREATE INDEX idx_engineers_region ON network_engineers (region);


-- ---------------------------------------------------------------------
-- sla_configuration
-- One row per priority, holding the response and resolution windows from
-- section 8 of the case study.
-- ---------------------------------------------------------------------
CREATE TABLE sla_configuration (
    sla_config_id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    priority                 VARCHAR(10)     NOT NULL,
    response_minutes         INT             NOT NULL,
    resolution_minutes       INT             NOT NULL,
    at_risk_threshold_pct    INT             NOT NULL DEFAULT 80,
    description              VARCHAR(200)    NULL,
    active                   BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at               DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                             ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT pk_sla_config PRIMARY KEY (sla_config_id),
    CONSTRAINT uq_sla_config_priority UNIQUE (priority),
    CONSTRAINT chk_sla_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT chk_sla_response CHECK (response_minutes > 0),
    CONSTRAINT chk_sla_resolution CHECK (resolution_minutes > 0),
    CONSTRAINT chk_sla_window CHECK (resolution_minutes >= response_minutes),
    CONSTRAINT chk_sla_threshold CHECK (at_risk_threshold_pct BETWEEN 1 AND 99)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------
-- trouble_tickets
-- ---------------------------------------------------------------------
CREATE TABLE trouble_tickets (
    ticket_id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ticket_number         VARCHAR(20)     NOT NULL,
    customer_id           BIGINT UNSIGNED NOT NULL,
    service_id            BIGINT UNSIGNED NOT NULL,
    category              VARCHAR(30)     NOT NULL,
    description           VARCHAR(1000)   NOT NULL,
    priority              VARCHAR(10)     NOT NULL,
    severity              VARCHAR(10)     NOT NULL,
    status                VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    assigned_engineer_id  BIGINT UNSIGNED NULL,
    escalation_level      VARCHAR(25)     NOT NULL DEFAULT 'ENGINEER',
    sla_status            VARCHAR(15)     NOT NULL DEFAULT 'WITHIN_SLA',
    created_date          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    assigned_date         DATETIME        NULL,
    sla_response_deadline DATETIME        NULL,
    sla_deadline          DATETIME        NULL,
    first_response_date   DATETIME        NULL,
    resolution_date       DATETIME        NULL,
    closed_date           DATETIME        NULL,
    root_cause            VARCHAR(1000)   NULL,
    resolution            VARCHAR(1000)   NULL,
    resolution_code       VARCHAR(30)     NULL,
    auto_created          BOOLEAN         NOT NULL DEFAULT FALSE,
    created_by            VARCHAR(50)     NOT NULL,
    created_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
                                          ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT pk_tickets PRIMARY KEY (ticket_id),
    CONSTRAINT uq_tickets_number UNIQUE (ticket_number),
    CONSTRAINT fk_tickets_customer FOREIGN KEY (customer_id)
        REFERENCES customers (customer_id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_tickets_service FOREIGN KEY (service_id)
        REFERENCES telecom_services (service_id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_tickets_engineer FOREIGN KEY (assigned_engineer_id)
        REFERENCES network_engineers (engineer_id) ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT chk_tickets_category CHECK (category IN
        ('NETWORK_OUTAGE', 'CALL_DROP', 'SLOW_DATA', 'NO_CONNECTIVITY', 'SIM_ISSUE',
         'BILLING', 'BROADBAND', 'ROAMING', 'ENTERPRISE_LINK', 'OTHER')),
    CONSTRAINT chk_tickets_priority CHECK (priority IN
        ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT chk_tickets_severity CHECK (severity IN
        ('INFO', 'WARNING', 'MINOR', 'MAJOR', 'CRITICAL')),
    CONSTRAINT chk_tickets_status CHECK (status IN
        ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'PENDING_CUSTOMER', 'ESCALATED',
         'RESOLVED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT chk_tickets_escalation CHECK (escalation_level IN
        ('ENGINEER', 'TEAM_LEAD', 'NETWORK_MANAGER', 'OPERATIONS_MANAGER')),
    CONSTRAINT chk_tickets_sla_status CHECK (sla_status IN
        ('WITHIN_SLA', 'AT_RISK', 'BREACHED')),
    CONSTRAINT chk_tickets_resolution_code CHECK (resolution_code IS NULL OR resolution_code IN
        ('HARDWARE_FAILURE', 'CONFIGURATION_ERROR', 'NETWORK_CONGESTION', 'SOFTWARE_FAILURE',
         'FIBER_CUT', 'POWER_FAILURE', 'CUSTOMER_DEVICE', 'UNKNOWN')),
    -- A resolved ticket must carry both a resolution timestamp and a code.
    CONSTRAINT chk_tickets_resolved_complete CHECK (
        status NOT IN ('RESOLVED', 'CLOSED')
        OR (resolution_date IS NOT NULL AND resolution_code IS NOT NULL)),
    CONSTRAINT chk_tickets_dates CHECK (
        resolution_date IS NULL OR resolution_date >= created_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_tickets_customer ON trouble_tickets (customer_id);
CREATE INDEX idx_tickets_service ON trouble_tickets (service_id);
CREATE INDEX idx_tickets_engineer ON trouble_tickets (assigned_engineer_id);
CREATE INDEX idx_tickets_status ON trouble_tickets (status);
CREATE INDEX idx_tickets_priority ON trouble_tickets (priority);
CREATE INDEX idx_tickets_category ON trouble_tickets (category);
CREATE INDEX idx_tickets_sla_status ON trouble_tickets (sla_status);
CREATE INDEX idx_tickets_created ON trouble_tickets (created_date);
CREATE INDEX idx_tickets_deadline ON trouble_tickets (sla_deadline);
-- Serves the SLA monitor, which repeatedly scans open tickets by deadline.
CREATE INDEX idx_tickets_monitor ON trouble_tickets (status, sla_deadline);


-- ---------------------------------------------------------------------
-- ticket_status_history
-- ---------------------------------------------------------------------
CREATE TABLE ticket_status_history (
    history_id   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ticket_id    BIGINT UNSIGNED NOT NULL,
    old_status   VARCHAR(20)     NULL,
    new_status   VARCHAR(20)     NOT NULL,
    changed_by   VARCHAR(50)     NOT NULL,
    changed_date DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    remarks      VARCHAR(500)    NULL,

    CONSTRAINT pk_status_history PRIMARY KEY (history_id),
    CONSTRAINT fk_status_history_ticket FOREIGN KEY (ticket_id)
        REFERENCES trouble_tickets (ticket_id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT chk_history_new_status CHECK (new_status IN
        ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'PENDING_CUSTOMER', 'ESCALATED',
         'RESOLVED', 'CLOSED', 'CANCELLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_status_history_ticket ON ticket_status_history (ticket_id, changed_date);


-- ---------------------------------------------------------------------
-- escalation_history
-- ---------------------------------------------------------------------
CREATE TABLE escalation_history (
    escalation_id   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ticket_id       BIGINT UNSIGNED NOT NULL,
    from_level      VARCHAR(25)     NOT NULL,
    to_level        VARCHAR(25)     NOT NULL,
    reason          VARCHAR(500)    NOT NULL,
    escalation_date DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    escalated_by    VARCHAR(50)     NOT NULL,
    auto_escalated  BOOLEAN         NOT NULL DEFAULT FALSE,

    CONSTRAINT pk_escalation PRIMARY KEY (escalation_id),
    CONSTRAINT fk_escalation_ticket FOREIGN KEY (ticket_id)
        REFERENCES trouble_tickets (ticket_id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT chk_escalation_from CHECK (from_level IN
        ('ENGINEER', 'TEAM_LEAD', 'NETWORK_MANAGER', 'OPERATIONS_MANAGER')),
    CONSTRAINT chk_escalation_to CHECK (to_level IN
        ('ENGINEER', 'TEAM_LEAD', 'NETWORK_MANAGER', 'OPERATIONS_MANAGER')),
    CONSTRAINT chk_escalation_direction CHECK (from_level <> to_level)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_escalation_ticket ON escalation_history (ticket_id, escalation_date);


-- ---------------------------------------------------------------------
-- network_events
-- Alarms raised by network elements, consumed by the background event
-- processor which may open a ticket from them.
-- ---------------------------------------------------------------------
CREATE TABLE network_events (
    event_id        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_reference VARCHAR(20)     NOT NULL,
    network_node    VARCHAR(50)     NOT NULL,
    event_type      VARCHAR(30)     NOT NULL,
    severity        VARCHAR(10)     NOT NULL,
    event_time      DATETIME        NOT NULL,
    event_status    VARCHAR(20)     NOT NULL DEFAULT 'RECEIVED',
    region          VARCHAR(20)     NULL,
    details         VARCHAR(500)    NULL,
    ticket_id       BIGINT UNSIGNED NULL,
    processed_date  DATETIME        NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_events PRIMARY KEY (event_id),
    CONSTRAINT uq_events_reference UNIQUE (event_reference),
    CONSTRAINT fk_events_ticket FOREIGN KEY (ticket_id)
        REFERENCES trouble_tickets (ticket_id) ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT chk_events_type CHECK (event_type IN
        ('LINK_DOWN', 'LINK_UP', 'NODE_UNREACHABLE', 'HIGH_LATENCY', 'PACKET_LOSS',
         'POWER_OUTAGE', 'CONGESTION', 'HARDWARE_ALARM', 'CONFIG_CHANGE', 'HEARTBEAT')),
    CONSTRAINT chk_events_severity CHECK (severity IN
        ('INFO', 'WARNING', 'MINOR', 'MAJOR', 'CRITICAL')),
    CONSTRAINT chk_events_status CHECK (event_status IN
        ('RECEIVED', 'PROCESSING', 'TICKET_CREATED', 'IGNORED', 'FAILED')),
    CONSTRAINT chk_events_region CHECK (region IS NULL OR region IN
        ('NORTH', 'SOUTH', 'EAST', 'WEST', 'CENTRAL'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_events_status ON network_events (event_status);
CREATE INDEX idx_events_time ON network_events (event_time);
CREATE INDEX idx_events_node ON network_events (network_node);


-- ---------------------------------------------------------------------
-- notifications
-- ---------------------------------------------------------------------
CREATE TABLE notifications (
    notification_id   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    recipient_id      BIGINT UNSIGNED NOT NULL,
    recipient_role    VARCHAR(20)     NOT NULL,
    notification_type VARCHAR(30)     NOT NULL,
    message           VARCHAR(500)    NOT NULL,
    ticket_id         BIGINT UNSIGNED NULL,
    read_status       BOOLEAN         NOT NULL DEFAULT FALSE,
    created_date      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_date         DATETIME        NULL,

    CONSTRAINT pk_notifications PRIMARY KEY (notification_id),
    CONSTRAINT fk_notifications_user FOREIGN KEY (recipient_id)
        REFERENCES users (user_id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_notifications_ticket FOREIGN KEY (ticket_id)
        REFERENCES trouble_tickets (ticket_id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT chk_notifications_type CHECK (notification_type IN
        ('TICKET_CREATED', 'ENGINEER_ASSIGNED', 'SLA_WARNING', 'SLA_BREACH',
         'TICKET_ESCALATED', 'TICKET_RESOLVED', 'TICKET_CLOSED')),
    CONSTRAINT chk_notifications_role CHECK (recipient_role IN
        ('CUSTOMER', 'SERVICE_DESK', 'NETWORK_ENGINEER', 'NETWORK_MANAGER'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_notifications_recipient ON notifications (recipient_id, read_status);
CREATE INDEX idx_notifications_created ON notifications (created_date);


-- ---------------------------------------------------------------------
-- feedback
-- One rating per ticket, enforced by the unique constraint.
-- ---------------------------------------------------------------------
CREATE TABLE feedback (
    feedback_id    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    ticket_id      BIGINT UNSIGNED NOT NULL,
    customer_id    BIGINT UNSIGNED NOT NULL,
    rating         INT             NOT NULL,
    comments       VARCHAR(500)    NULL,
    submitted_date DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_feedback PRIMARY KEY (feedback_id),
    CONSTRAINT uq_feedback_ticket UNIQUE (ticket_id),
    CONSTRAINT fk_feedback_ticket FOREIGN KEY (ticket_id)
        REFERENCES trouble_tickets (ticket_id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_feedback_customer FOREIGN KEY (customer_id)
        REFERENCES customers (customer_id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT chk_feedback_rating CHECK (rating BETWEEN 1 AND 5)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_feedback_customer ON feedback (customer_id);


-- ---------------------------------------------------------------------
-- audit_log
-- Deliberately carries no foreign keys: audit rows must survive the
-- deletion of whatever they describe.
-- ---------------------------------------------------------------------
CREATE TABLE audit_log (
    audit_id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    entity_type    VARCHAR(40)     NOT NULL,
    entity_id      VARCHAR(40)     NOT NULL,
    action         VARCHAR(40)     NOT NULL,
    performed_by   VARCHAR(50)     NOT NULL,
    performed_date DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    old_value      VARCHAR(500)    NULL,
    new_value      VARCHAR(500)    NULL,
    details        VARCHAR(500)    NULL,

    CONSTRAINT pk_audit PRIMARY KEY (audit_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_audit_entity ON audit_log (entity_type, entity_id);
CREATE INDEX idx_audit_date ON audit_log (performed_date);
CREATE INDEX idx_audit_user ON audit_log (performed_by);


-- ---------------------------------------------------------------------
-- login_history
-- username is stored alongside the foreign key so that failed attempts
-- against an unknown username can still be recorded.
-- ---------------------------------------------------------------------
CREATE TABLE login_history (
    login_id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id        BIGINT UNSIGNED NULL,
    username       VARCHAR(50)     NOT NULL,
    login_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    logout_time    DATETIME        NULL,
    login_status   VARCHAR(20)     NOT NULL,
    failure_reason VARCHAR(200)    NULL,
    ip_address     VARCHAR(45)     NULL,

    CONSTRAINT pk_login_history PRIMARY KEY (login_id),
    CONSTRAINT fk_login_history_user FOREIGN KEY (user_id)
        REFERENCES users (user_id) ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT chk_login_status CHECK (login_status IN
        ('SUCCESS', 'FAILED', 'LOCKED', 'OTP_FAILED', 'CAPTCHA_FAILED')),
    CONSTRAINT chk_login_times CHECK (logout_time IS NULL OR logout_time >= login_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_login_history_user ON login_history (user_id, login_time);
CREATE INDEX idx_login_history_username ON login_history (username);
CREATE INDEX idx_login_history_status ON login_history (login_status);
