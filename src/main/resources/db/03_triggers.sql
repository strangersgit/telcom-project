-- =====================================================================
-- TSATMS - 03 - Triggers
-- ---------------------------------------------------------------------
-- These triggers are deliberately confined to data hygiene: defaulting
-- derived columns and stamping lifecycle timestamps.
--
-- Ticket status history and the operational audit trail are written
-- explicitly by the Java service layer, because section 19 of the case
-- study requires them to be steps inside the assignment transaction. No
-- trigger duplicates that work, so there is no risk of double entries.
-- =====================================================================

USE `${db.schema}`;

DROP TRIGGER IF EXISTS trg_tickets_before_insert;
DROP TRIGGER IF EXISTS trg_tickets_before_update;
DROP TRIGGER IF EXISTS trg_engineers_after_update;
DROP TRIGGER IF EXISTS trg_notifications_before_update;

DELIMITER $$

-- ---------------------------------------------------------------------
-- Every ticket gets its SLA deadlines at birth, derived from the
-- priority band, so a row inserted by any route is never left without
-- an SLA clock.
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_tickets_before_insert
    BEFORE INSERT ON trouble_tickets
    FOR EACH ROW
BEGIN
    IF NEW.created_date IS NULL THEN
        SET NEW.created_date = NOW();
    END IF;

    IF NEW.sla_deadline IS NULL THEN
        SET NEW.sla_deadline = fn_sla_deadline(NEW.priority, NEW.created_date);
    END IF;

    IF NEW.sla_response_deadline IS NULL THEN
        SET NEW.sla_response_deadline = fn_sla_response_deadline(NEW.priority, NEW.created_date);
    END IF;
END$$

-- ---------------------------------------------------------------------
-- Stamps the lifecycle timestamps that follow mechanically from a status
-- or assignment change, so no caller can forget them.
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_tickets_before_update
    BEFORE UPDATE ON trouble_tickets
    FOR EACH ROW
BEGIN
    IF NEW.status = 'RESOLVED' AND OLD.status <> 'RESOLVED'
       AND NEW.resolution_date IS NULL THEN
        SET NEW.resolution_date = NOW();
    END IF;

    IF NEW.status = 'CLOSED' AND OLD.status <> 'CLOSED'
       AND NEW.closed_date IS NULL THEN
        SET NEW.closed_date = NOW();
    END IF;

    IF NEW.assigned_engineer_id IS NOT NULL
       AND OLD.assigned_engineer_id IS NULL
       AND NEW.assigned_date IS NULL THEN
        SET NEW.assigned_date = NOW();
    END IF;

    -- A reopened ticket loses its closure timestamps and the resolution
    -- that did not hold. Clearing resolution_date is not tidiness: every
    -- measurement of how long a ticket took reads that column, and the SLA
    -- engine stops its clock there, so a reopened ticket that kept it would
    -- sit in progress accruing no elapsed time. The code and the text go
    -- with it, because a ticket being worked again has no resolution to
    -- report and the resolution code analysis would otherwise count the
    -- same fault as fixed twice.
    --
    -- The Java layer clears these columns explicitly in the same statement
    -- that moves the status. This clause covers every other route into the
    -- table.
    IF NEW.status NOT IN ('RESOLVED', 'CLOSED') AND OLD.status IN ('RESOLVED', 'CLOSED') THEN
        SET NEW.closed_date = NULL;
        SET NEW.resolution_date = NULL;
        SET NEW.resolution_code = NULL;
        SET NEW.resolution = NULL;
    END IF;

    -- Changing priority moves the deadline with it.
    IF NEW.priority <> OLD.priority THEN
        SET NEW.sla_deadline = fn_sla_deadline(NEW.priority, NEW.created_date);
        SET NEW.sla_response_deadline = fn_sla_response_deadline(NEW.priority, NEW.created_date);
    END IF;
END$$

-- ---------------------------------------------------------------------
-- Roster changes are audited at database level. The Java layer audits
-- ticket operations; this covers engineer availability regardless of how
-- it was changed.
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_engineers_after_update
    AFTER UPDATE ON network_engineers
    FOR EACH ROW
BEGIN
    IF NEW.availability <> OLD.availability THEN
        INSERT INTO audit_log
            (entity_type, entity_id, action, performed_by, old_value, new_value, details)
        VALUES
            ('NETWORK_ENGINEER', NEW.employee_code, 'AVAILABILITY_CHANGED', 'DB_TRIGGER',
             OLD.availability, NEW.availability,
             CONCAT('Active tickets at time of change: ', NEW.active_ticket_count));
    END IF;
END$$

-- ---------------------------------------------------------------------
-- Records when a notification was actually read.
-- ---------------------------------------------------------------------
CREATE TRIGGER trg_notifications_before_update
    BEFORE UPDATE ON notifications
    FOR EACH ROW
BEGIN
    IF NEW.read_status = TRUE AND OLD.read_status = FALSE AND NEW.read_date IS NULL THEN
        SET NEW.read_date = NOW();
    END IF;
END$$

DELIMITER ;
