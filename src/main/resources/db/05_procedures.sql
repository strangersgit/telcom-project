-- =====================================================================
-- TSATMS - 05 - Stored procedures
-- ---------------------------------------------------------------------
-- sp_assign_engineer mirrors the transaction described in section 19 of
-- the case study, server side. The application itself performs that
-- sequence in Java over JDBC, as the case study requires; this procedure
-- is the database-side equivalent and is what the SQL demonstration
-- script exercises.
--
-- Where a procedure writes to audit_log it uses the same action name the
-- Java route uses for the same event, so a search of the trail finds every
-- escalation whichever route performed it.
-- =====================================================================

USE `${db.schema}`;

DROP PROCEDURE IF EXISTS sp_assign_engineer;
DROP PROCEDURE IF EXISTS sp_escalate_ticket;
DROP PROCEDURE IF EXISTS sp_resolve_ticket;
DROP PROCEDURE IF EXISTS sp_recommend_engineers;
DROP PROCEDURE IF EXISTS sp_ticket_volume_report;
DROP PROCEDURE IF EXISTS sp_manager_dashboard;

DELIMITER $$

-- ---------------------------------------------------------------------
-- Validate, assign, update, record history, notify and audit, as one
-- atomic unit. Any failure rolls the whole thing back.
-- ---------------------------------------------------------------------
CREATE PROCEDURE sp_assign_engineer(
    IN  p_ticket_id   BIGINT UNSIGNED,
    IN  p_engineer_id BIGINT UNSIGNED,
    IN  p_actor       VARCHAR(50),
    OUT p_status      VARCHAR(20),
    OUT p_message     VARCHAR(255))
BEGIN
    DECLARE v_old_status      VARCHAR(20);
    DECLARE v_ticket_number   VARCHAR(20);
    DECLARE v_customer_id     BIGINT UNSIGNED;
    DECLARE v_recipient       BIGINT UNSIGNED;
    DECLARE v_employee_code   VARCHAR(20);
    DECLARE v_availability    VARCHAR(20);
    DECLARE v_active_count    INT;
    DECLARE v_capacity        INT;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
        BEGIN
            ROLLBACK;
            SET p_status = 'FAILED';
            SET p_message = 'Assignment rolled back after a database error';
        END;

    -- Step 1: validate the ticket.
    SELECT status, ticket_number, customer_id
    INTO v_old_status, v_ticket_number, v_customer_id
    FROM trouble_tickets
    WHERE ticket_id = p_ticket_id;

    IF v_ticket_number IS NULL THEN
        SET p_status = 'FAILED';
        SET p_message = 'Ticket does not exist';
    ELSEIF v_old_status IN ('RESOLVED', 'CLOSED', 'CANCELLED') THEN
        SET p_status = 'FAILED';
        SET p_message = CONCAT('Ticket is already ', v_old_status);
    ELSE
        -- Step 2: validate the engineer.
        SELECT employee_code, availability, active_ticket_count, max_ticket_capacity
        INTO v_employee_code, v_availability, v_active_count, v_capacity
        FROM network_engineers
        WHERE engineer_id = p_engineer_id;

        IF v_employee_code IS NULL THEN
            SET p_status = 'FAILED';
            SET p_message = 'Engineer does not exist';
        ELSEIF v_availability <> 'AVAILABLE' THEN
            SET p_status = 'FAILED';
            SET p_message = CONCAT('Engineer is ', v_availability);
        ELSEIF v_active_count >= v_capacity THEN
            SET p_status = 'FAILED';
            SET p_message = 'Engineer is at maximum capacity';
        ELSE
            START TRANSACTION;

            -- Step 3: assign and move the ticket forward.
            UPDATE trouble_tickets
            SET assigned_engineer_id = p_engineer_id,
                status               = 'ASSIGNED',
                assigned_date        = NOW()
            WHERE ticket_id = p_ticket_id;

            -- Step 4: keep the engineer's workload aggregate in step.
            UPDATE network_engineers
            SET active_ticket_count = active_ticket_count + 1,
                availability        = CASE
                                          WHEN active_ticket_count + 1 >= max_ticket_capacity
                                              THEN 'BUSY'
                                          ELSE availability
                    END
            WHERE engineer_id = p_engineer_id;

            -- Step 5: status history.
            INSERT INTO ticket_status_history
                (ticket_id, old_status, new_status, changed_by, remarks)
            VALUES (p_ticket_id, v_old_status, 'ASSIGNED', p_actor,
                    CONCAT('Assigned to engineer ', v_employee_code));

            -- Step 6: notify the customer, when they hold a login.
            SELECT user_id INTO v_recipient
            FROM customers
            WHERE customer_id = v_customer_id;

            IF v_recipient IS NOT NULL THEN
                INSERT INTO notifications
                    (recipient_id, recipient_role, notification_type, message, ticket_id)
                VALUES (v_recipient, 'CUSTOMER', 'ENGINEER_ASSIGNED',
                        CONCAT('Ticket ', v_ticket_number,
                               ' has been assigned to engineer ', v_employee_code, '.'),
                        p_ticket_id);
            END IF;

            -- Step 7: audit record.
            INSERT INTO audit_log
                (entity_type, entity_id, action, performed_by, old_value, new_value, details)
            VALUES ('TROUBLE_TICKET', v_ticket_number, 'ENGINEER_ASSIGNED', p_actor,
                    v_old_status, 'ASSIGNED',
                    CONCAT('Engineer ', v_employee_code, ' assigned via stored procedure'));

            COMMIT;

            SET p_status = 'SUCCESS';
            SET p_message = CONCAT('Ticket ', v_ticket_number,
                                   ' assigned to ', v_employee_code);
        END IF;
    END IF;
END$$

-- ---------------------------------------------------------------------
-- Moves a ticket one rung up the escalation ladder.
-- ---------------------------------------------------------------------
CREATE PROCEDURE sp_escalate_ticket(
    IN  p_ticket_id BIGINT UNSIGNED,
    IN  p_reason    VARCHAR(500),
    IN  p_actor     VARCHAR(50),
    IN  p_automatic BOOLEAN,
    OUT p_status    VARCHAR(20),
    OUT p_message   VARCHAR(255))
BEGIN
    DECLARE v_current_level VARCHAR(25);
    DECLARE v_next_level    VARCHAR(25);
    DECLARE v_old_status    VARCHAR(20);
    DECLARE v_ticket_number VARCHAR(20);

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
        BEGIN
            ROLLBACK;
            SET p_status = 'FAILED';
            SET p_message = 'Escalation rolled back after a database error';
        END;

    SELECT escalation_level, status, ticket_number
    INTO v_current_level, v_old_status, v_ticket_number
    FROM trouble_tickets
    WHERE ticket_id = p_ticket_id;

    IF v_ticket_number IS NULL THEN
        SET p_status = 'FAILED';
        SET p_message = 'Ticket does not exist';
    ELSE
        SET v_next_level = CASE v_current_level
                               WHEN 'ENGINEER' THEN 'TEAM_LEAD'
                               WHEN 'TEAM_LEAD' THEN 'NETWORK_MANAGER'
                               WHEN 'NETWORK_MANAGER' THEN 'OPERATIONS_MANAGER'
                               ELSE NULL
            END;

        IF v_next_level IS NULL THEN
            SET p_status = 'FAILED';
            SET p_message = 'Ticket is already at the highest escalation level';
        ELSE
            START TRANSACTION;

            UPDATE trouble_tickets
            SET escalation_level = v_next_level,
                status           = 'ESCALATED'
            WHERE ticket_id = p_ticket_id;

            INSERT INTO escalation_history
                (ticket_id, from_level, to_level, reason, escalated_by, auto_escalated)
            VALUES (p_ticket_id, v_current_level, v_next_level, p_reason, p_actor, p_automatic);

            INSERT INTO ticket_status_history
                (ticket_id, old_status, new_status, changed_by, remarks)
            VALUES (p_ticket_id, v_old_status, 'ESCALATED', p_actor,
                    CONCAT('Escalated from ', v_current_level, ' to ', v_next_level));

            INSERT INTO audit_log
                (entity_type, entity_id, action, performed_by, old_value, new_value, details)
            VALUES ('TROUBLE_TICKET', v_ticket_number, 'TICKET_ESCALATED', p_actor,
                    v_current_level, v_next_level, p_reason);

            COMMIT;

            SET p_status = 'SUCCESS';
            SET p_message = CONCAT('Ticket ', v_ticket_number, ' escalated to ', v_next_level);
        END IF;
    END IF;
END$$

-- ---------------------------------------------------------------------
-- Records a resolution and releases the engineer's capacity.
-- ---------------------------------------------------------------------
CREATE PROCEDURE sp_resolve_ticket(
    IN  p_ticket_id       BIGINT UNSIGNED,
    IN  p_root_cause      VARCHAR(1000),
    IN  p_resolution      VARCHAR(1000),
    IN  p_resolution_code VARCHAR(30),
    IN  p_actor           VARCHAR(50),
    OUT p_status          VARCHAR(20),
    OUT p_message         VARCHAR(255))
BEGIN
    DECLARE v_old_status    VARCHAR(20);
    DECLARE v_ticket_number VARCHAR(20);
    DECLARE v_engineer_id   BIGINT UNSIGNED;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
        BEGIN
            ROLLBACK;
            SET p_status = 'FAILED';
            SET p_message = 'Resolution rolled back after a database error';
        END;

    SELECT status, ticket_number, assigned_engineer_id
    INTO v_old_status, v_ticket_number, v_engineer_id
    FROM trouble_tickets
    WHERE ticket_id = p_ticket_id;

    IF v_ticket_number IS NULL THEN
        SET p_status = 'FAILED';
        SET p_message = 'Ticket does not exist';
    ELSEIF v_old_status IN ('RESOLVED', 'CLOSED', 'CANCELLED') THEN
        SET p_status = 'FAILED';
        SET p_message = CONCAT('Ticket is already ', v_old_status);
    ELSE
        START TRANSACTION;

        UPDATE trouble_tickets
        SET status          = 'RESOLVED',
            root_cause      = p_root_cause,
            resolution      = p_resolution,
            resolution_code = p_resolution_code,
            resolution_date = NOW()
        WHERE ticket_id = p_ticket_id;

        IF v_engineer_id IS NOT NULL THEN
            UPDATE network_engineers
            SET active_ticket_count = GREATEST(active_ticket_count - 1, 0),
                availability        = CASE
                                          WHEN availability = 'BUSY' THEN 'AVAILABLE'
                                          ELSE availability
                    END
            WHERE engineer_id = v_engineer_id;
        END IF;

        INSERT INTO ticket_status_history
            (ticket_id, old_status, new_status, changed_by, remarks)
        VALUES (p_ticket_id, v_old_status, 'RESOLVED', p_actor, p_resolution_code);

        INSERT INTO audit_log
            (entity_type, entity_id, action, performed_by, old_value, new_value, details)
        VALUES ('TROUBLE_TICKET', v_ticket_number, 'TICKET_RESOLVED', p_actor,
                v_old_status, 'RESOLVED', p_resolution_code);

        COMMIT;

        SET p_status = 'SUCCESS';
        SET p_message = CONCAT('Ticket ', v_ticket_number, ' resolved');
    END IF;
END$$

-- ---------------------------------------------------------------------
-- The database-side twin of the Java recommendation engine: available
-- engineers with the right skill, lightest workload first, most
-- experienced breaking the tie.
-- ---------------------------------------------------------------------
CREATE PROCEDURE sp_recommend_engineers(
    IN p_specialization VARCHAR(30),
    IN p_region         VARCHAR(20),
    IN p_limit          INT)
BEGIN
    SELECT
        e.engineer_id,
        e.employee_code,
        e.engineer_name,
        e.specialization,
        e.region,
        e.experience_years,
        e.active_ticket_count,
        e.max_ticket_capacity,
        (e.max_ticket_capacity - e.active_ticket_count) AS spare_capacity
    FROM network_engineers e
    WHERE e.specialization = p_specialization
      AND (p_region IS NULL OR e.region = p_region)
      AND e.availability = 'AVAILABLE'
      AND e.active_ticket_count < e.max_ticket_capacity
    ORDER BY e.active_ticket_count ASC, e.experience_years DESC
    LIMIT p_limit;
END$$

-- ---------------------------------------------------------------------
-- Ticket volume between two dates, broken down by day and priority.
-- ---------------------------------------------------------------------
CREATE PROCEDURE sp_ticket_volume_report(
    IN p_start_date DATE,
    IN p_end_date   DATE)
BEGIN
    SELECT
        DATE(t.created_date)                                        AS report_date,
        COUNT(*)                                                    AS total_raised,
        SUM(CASE WHEN t.priority = 'CRITICAL' THEN 1 ELSE 0 END)    AS critical,
        SUM(CASE WHEN t.priority = 'HIGH' THEN 1 ELSE 0 END)        AS high,
        SUM(CASE WHEN t.priority = 'MEDIUM' THEN 1 ELSE 0 END)      AS medium,
        SUM(CASE WHEN t.priority = 'LOW' THEN 1 ELSE 0 END)         AS low,
        SUM(CASE WHEN t.status IN ('RESOLVED', 'CLOSED') THEN 1 ELSE 0 END) AS completed,
        SUM(CASE WHEN t.auto_created = TRUE THEN 1 ELSE 0 END)      AS raised_by_system
    FROM trouble_tickets t
    WHERE DATE(t.created_date) BETWEEN p_start_date AND p_end_date
    GROUP BY DATE(t.created_date)
    ORDER BY report_date;
END$$

-- ---------------------------------------------------------------------
-- The section 15 dashboard figures.
-- ---------------------------------------------------------------------
CREATE PROCEDURE sp_manager_dashboard()
BEGIN
    SELECT * FROM vw_manager_dashboard;
END$$

DELIMITER ;
