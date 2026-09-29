-- =====================================================================
-- TSATMS - 02 - Stored functions
-- ---------------------------------------------------------------------
-- The SLA arithmetic from section 8 of the case study, expressed once in
-- the database so that views, triggers and ad hoc reporting queries all
-- agree with the Java SLA service.
-- =====================================================================

USE `${db.schema}`;

DROP FUNCTION IF EXISTS fn_sla_response_deadline;
DROP FUNCTION IF EXISTS fn_sla_deadline;
DROP FUNCTION IF EXISTS fn_sla_status;
DROP FUNCTION IF EXISTS fn_resolution_hours;
DROP FUNCTION IF EXISTS fn_minutes_remaining;

DELIMITER $$

-- ---------------------------------------------------------------------
-- Resolution deadline for a ticket of the given priority.
-- ---------------------------------------------------------------------
CREATE FUNCTION fn_sla_deadline(
    p_priority VARCHAR(10) CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
    p_created  DATETIME)
    RETURNS DATETIME
    READS SQL DATA
BEGIN
    DECLARE v_minutes INT DEFAULT NULL;

    SELECT resolution_minutes INTO v_minutes
    FROM sla_configuration
    WHERE priority = p_priority AND active = TRUE
    LIMIT 1;

    IF v_minutes IS NULL THEN
        RETURN NULL;
    END IF;

    RETURN DATE_ADD(p_created, INTERVAL v_minutes MINUTE);
END$$

-- ---------------------------------------------------------------------
-- First response deadline for a ticket of the given priority.
-- ---------------------------------------------------------------------
CREATE FUNCTION fn_sla_response_deadline(
    p_priority VARCHAR(10) CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
    p_created  DATETIME)
    RETURNS DATETIME
    READS SQL DATA
BEGIN
    DECLARE v_minutes INT DEFAULT NULL;

    SELECT response_minutes INTO v_minutes
    FROM sla_configuration
    WHERE priority = p_priority AND active = TRUE
    LIMIT 1;

    IF v_minutes IS NULL THEN
        RETURN NULL;
    END IF;

    RETURN DATE_ADD(p_created, INTERVAL v_minutes MINUTE);
END$$

-- ---------------------------------------------------------------------
-- Live SLA standing of a ticket.
--
-- A finished ticket is judged on whether it was resolved before its
-- deadline. An open one is judged against the clock now, turning AT_RISK
-- once the configured percentage of its window has been consumed.
-- ---------------------------------------------------------------------
-- The parameter and return collations are pinned explicitly. Without
-- this they would inherit whatever collation the client connection
-- happened to use when the routine was created, and any later
-- comparison against a string literal could be rejected as an illegal
-- mix of collations.
CREATE FUNCTION fn_sla_status(
    p_priority   VARCHAR(10) CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
    p_created    DATETIME,
    p_deadline   DATETIME,
    p_resolution DATETIME,
    p_status     VARCHAR(20) CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci)
    RETURNS VARCHAR(15) CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci
    READS SQL DATA
BEGIN
    DECLARE v_threshold INT DEFAULT 80;
    DECLARE v_total_minutes INT;
    DECLARE v_elapsed_minutes INT;

    IF p_deadline IS NULL THEN
        RETURN 'WITHIN_SLA';
    END IF;

    -- Finished tickets are settled against the deadline they had.
    IF p_status IN ('RESOLVED', 'CLOSED') THEN
        IF p_resolution IS NOT NULL AND p_resolution > p_deadline THEN
            RETURN 'BREACHED';
        END IF;
        RETURN 'WITHIN_SLA';
    END IF;

    IF p_status = 'CANCELLED' THEN
        RETURN 'WITHIN_SLA';
    END IF;

    IF NOW() > p_deadline THEN
        RETURN 'BREACHED';
    END IF;

    SELECT at_risk_threshold_pct INTO v_threshold
    FROM sla_configuration
    WHERE priority = p_priority AND active = TRUE
    LIMIT 1;

    SET v_total_minutes = TIMESTAMPDIFF(MINUTE, p_created, p_deadline);
    SET v_elapsed_minutes = TIMESTAMPDIFF(MINUTE, p_created, NOW());

    IF v_total_minutes > 0
       AND (v_elapsed_minutes * 100.0 / v_total_minutes) >= IFNULL(v_threshold, 80) THEN
        RETURN 'AT_RISK';
    END IF;

    RETURN 'WITHIN_SLA';
END$$

-- ---------------------------------------------------------------------
-- Elapsed resolution time in hours, used by the average resolution
-- report. Returns NULL while a ticket is still open.
-- ---------------------------------------------------------------------
CREATE FUNCTION fn_resolution_hours(p_created DATETIME, p_resolution DATETIME)
    RETURNS DECIMAL(10, 2)
    DETERMINISTIC
BEGIN
    IF p_created IS NULL OR p_resolution IS NULL THEN
        RETURN NULL;
    END IF;
    RETURN ROUND(TIMESTAMPDIFF(MINUTE, p_created, p_resolution) / 60.0, 2);
END$$

-- ---------------------------------------------------------------------
-- Minutes left before the deadline. Negative once breached.
-- ---------------------------------------------------------------------
CREATE FUNCTION fn_minutes_remaining(p_deadline DATETIME)
    RETURNS INT
    DETERMINISTIC
BEGIN
    IF p_deadline IS NULL THEN
        RETURN NULL;
    END IF;
    RETURN TIMESTAMPDIFF(MINUTE, NOW(), p_deadline);
END$$

DELIMITER ;
