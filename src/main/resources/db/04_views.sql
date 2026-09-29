-- =====================================================================
-- TSATMS - 04 - Views
-- ---------------------------------------------------------------------
-- Reporting shapes that would otherwise be repeated across the DAO
-- layer. Between them these views exercise INNER JOIN, LEFT JOIN,
-- GROUP BY, HAVING, ORDER BY, CASE, subqueries, aggregate functions and
-- date functions.
-- =====================================================================

USE `${db.schema}`;

DROP VIEW IF EXISTS vw_manager_dashboard;
DROP VIEW IF EXISTS vw_customer_repeat_incidents;
DROP VIEW IF EXISTS vw_category_incidents;
DROP VIEW IF EXISTS vw_sla_compliance;
DROP VIEW IF EXISTS vw_engineer_workload;
DROP VIEW IF EXISTS vw_open_tickets;
DROP VIEW IF EXISTS vw_ticket_details;


-- ---------------------------------------------------------------------
-- Every ticket with its customer, service and engineer resolved.
-- LEFT JOIN on the engineer because an unassigned ticket must still
-- appear.
-- ---------------------------------------------------------------------
CREATE VIEW vw_ticket_details AS
SELECT
    t.ticket_id,
    t.ticket_number,
    c.customer_number,
    c.customer_name,
    c.customer_type,
    c.city,
    c.region                AS customer_region,
    s.service_code,
    s.service_name,
    s.service_type,
    t.category,
    t.description,
    t.priority,
    t.severity,
    t.status,
    t.escalation_level,
    e.employee_code         AS engineer_code,
    e.engineer_name,
    e.specialization        AS engineer_specialization,
    e.region                AS engineer_region,
    t.created_date,
    t.assigned_date,
    t.sla_response_deadline,
    t.sla_deadline,
    t.resolution_date,
    t.closed_date,
    t.root_cause,
    t.resolution,
    t.resolution_code,
    t.auto_created,
    fn_sla_status(t.priority, t.created_date, t.sla_deadline,
                  t.resolution_date, t.status)      AS live_sla_status,
    fn_minutes_remaining(t.sla_deadline)            AS minutes_remaining,
    fn_resolution_hours(t.created_date, t.resolution_date) AS resolution_hours
FROM trouble_tickets t
         INNER JOIN customers c ON c.customer_id = t.customer_id
         INNER JOIN telecom_services s ON s.service_id = t.service_id
         LEFT JOIN network_engineers e ON e.engineer_id = t.assigned_engineer_id;


-- ---------------------------------------------------------------------
-- Work in flight, ordered so the most urgent appears first. This is the
-- backing query for the service desk queue.
-- ---------------------------------------------------------------------
CREATE VIEW vw_open_tickets AS
SELECT
    t.ticket_id,
    t.ticket_number,
    c.customer_number,
    c.customer_name,
    s.service_name,
    t.category,
    t.priority,
    t.severity,
    t.status,
    t.escalation_level,
    IFNULL(e.employee_code, 'UNASSIGNED')  AS engineer_code,
    t.created_date,
    t.sla_deadline,
    fn_sla_status(t.priority, t.created_date, t.sla_deadline,
                  t.resolution_date, t.status)  AS sla_status,
    fn_minutes_remaining(t.sla_deadline)        AS minutes_remaining,
    CASE t.priority
        WHEN 'CRITICAL' THEN 4
        WHEN 'HIGH'     THEN 3
        WHEN 'MEDIUM'   THEN 2
        ELSE 1
        END                                     AS priority_weight
FROM trouble_tickets t
         INNER JOIN customers c ON c.customer_id = t.customer_id
         INNER JOIN telecom_services s ON s.service_id = t.service_id
         LEFT JOIN network_engineers e ON e.engineer_id = t.assigned_engineer_id
WHERE t.status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED');


-- ---------------------------------------------------------------------
-- Engineer roster with live workload and performance. LEFT JOIN so an
-- engineer who has never been assigned anything still shows, with zeroes
-- rather than vanishing from the report.
-- ---------------------------------------------------------------------
CREATE VIEW vw_engineer_workload AS
SELECT
    e.engineer_id,
    e.employee_code,
    e.engineer_name,
    e.specialization,
    e.region,
    e.experience_years,
    e.availability,
    e.active_ticket_count,
    e.max_ticket_capacity,
    COUNT(t.ticket_id)                                              AS total_assigned,
    SUM(CASE WHEN t.status IN ('RESOLVED', 'CLOSED') THEN 1 ELSE 0 END) AS total_resolved,
    SUM(CASE WHEN t.status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED')
                 THEN 1 ELSE 0 END)                                 AS currently_open,
    SUM(CASE WHEN t.resolution_date IS NOT NULL
                  AND t.sla_deadline IS NOT NULL
                  AND t.resolution_date > t.sla_deadline
                 THEN 1 ELSE 0 END)                                 AS sla_breaches,
    ROUND(AVG(fn_resolution_hours(t.created_date, t.resolution_date)), 2)
                                                                    AS avg_resolution_hours
FROM network_engineers e
         LEFT JOIN trouble_tickets t ON t.assigned_engineer_id = e.engineer_id
GROUP BY e.engineer_id, e.employee_code, e.engineer_name, e.specialization,
         e.region, e.experience_years, e.availability,
         e.active_ticket_count, e.max_ticket_capacity;


-- ---------------------------------------------------------------------
-- SLA compliance per priority band.
-- ---------------------------------------------------------------------
CREATE VIEW vw_sla_compliance AS
SELECT
    t.priority,
    sc.response_minutes,
    sc.resolution_minutes,
    COUNT(*)                                                        AS total_tickets,
    SUM(CASE WHEN t.status IN ('RESOLVED', 'CLOSED') THEN 1 ELSE 0 END) AS completed_tickets,
    SUM(CASE WHEN t.resolution_date IS NOT NULL
                  AND t.resolution_date <= t.sla_deadline
                 THEN 1 ELSE 0 END)                                 AS met_sla,
    SUM(CASE WHEN t.resolution_date IS NOT NULL
                  AND t.resolution_date > t.sla_deadline
                 THEN 1 ELSE 0 END)                                 AS breached_sla,
    ROUND(
        100.0 * SUM(CASE WHEN t.resolution_date IS NOT NULL
                              AND t.resolution_date <= t.sla_deadline
                             THEN 1 ELSE 0 END)
            / NULLIF(SUM(CASE WHEN t.resolution_date IS NOT NULL THEN 1 ELSE 0 END), 0),
        2)                                                          AS compliance_pct,
    ROUND(AVG(fn_resolution_hours(t.created_date, t.resolution_date)), 2)
                                                                    AS avg_resolution_hours
FROM trouble_tickets t
         INNER JOIN sla_configuration sc ON sc.priority = t.priority
GROUP BY t.priority, sc.response_minutes, sc.resolution_minutes;


-- ---------------------------------------------------------------------
-- Incident volume by category, with the share of the total.
-- ---------------------------------------------------------------------
CREATE VIEW vw_category_incidents AS
SELECT
    t.category,
    COUNT(*)                                                        AS ticket_count,
    SUM(CASE WHEN t.priority = 'CRITICAL' THEN 1 ELSE 0 END)        AS critical_count,
    SUM(CASE WHEN t.auto_created = TRUE THEN 1 ELSE 0 END)          AS auto_created_count,
    ROUND(AVG(fn_resolution_hours(t.created_date, t.resolution_date)), 2)
                                                                    AS avg_resolution_hours,
    ROUND(100.0 * COUNT(*) / (SELECT COUNT(*) FROM trouble_tickets), 2)
                                                                    AS pct_of_total
FROM trouble_tickets t
GROUP BY t.category;


-- ---------------------------------------------------------------------
-- Customers raising repeated incidents. HAVING filters on the aggregate,
-- which a WHERE clause cannot do.
-- ---------------------------------------------------------------------
CREATE VIEW vw_customer_repeat_incidents AS
SELECT
    c.customer_id,
    c.customer_number,
    c.customer_name,
    c.customer_type,
    c.city,
    c.region,
    COUNT(t.ticket_id)                                              AS incident_count,
    SUM(CASE WHEN t.priority = 'CRITICAL' THEN 1 ELSE 0 END)        AS critical_incidents,
    MAX(t.created_date)                                             AS latest_incident,
    DATEDIFF(NOW(), MAX(t.created_date))                            AS days_since_last
FROM customers c
         INNER JOIN trouble_tickets t ON t.customer_id = c.customer_id
GROUP BY c.customer_id, c.customer_number, c.customer_name,
         c.customer_type, c.city, c.region
HAVING COUNT(t.ticket_id) > 1;


-- ---------------------------------------------------------------------
-- The single row behind the network manager dashboard in section 15.
-- ---------------------------------------------------------------------
CREATE VIEW vw_manager_dashboard AS
SELECT
    SUM(CASE WHEN status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED')
                 THEN 1 ELSE 0 END)                                 AS total_open_tickets,
    SUM(CASE WHEN priority = 'CRITICAL'
                  AND status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED')
                 THEN 1 ELSE 0 END)                                 AS critical_incidents,
    SUM(CASE WHEN fn_sla_status(priority, created_date, sla_deadline,
                                resolution_date, status) = 'AT_RISK'
                 THEN 1 ELSE 0 END)                                 AS sla_at_risk,
    SUM(CASE WHEN fn_sla_status(priority, created_date, sla_deadline,
                                resolution_date, status) = 'BREACHED'
                 THEN 1 ELSE 0 END)                                 AS sla_breached,
    SUM(CASE WHEN DATE(resolution_date) = CURDATE() THEN 1 ELSE 0 END) AS resolved_today,
    ROUND(AVG(fn_resolution_hours(created_date, resolution_date)), 2)  AS avg_resolution_hours,
    COUNT(*)                                                        AS total_tickets
FROM trouble_tickets;
