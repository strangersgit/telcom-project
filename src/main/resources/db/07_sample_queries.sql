-- =====================================================================
-- TSATMS - 07 - SQL demonstration script
-- ---------------------------------------------------------------------
-- Reference material, NOT run by the bootstrap: some statements modify
-- data and would disturb the seed. Run them individually in MySQL
-- Workbench.
--
-- Between them these queries cover every construct the case study
-- requires: SELECT, INSERT, UPDATE, DELETE, JOIN, LEFT JOIN, GROUP BY,
-- HAVING, ORDER BY, subqueries, CASE, aggregate functions and date
-- functions, plus the views, stored functions and procedures.
-- =====================================================================

USE `${db.schema}`;


-- ---------------------------------------------------------------------
-- 1. SELECT with WHERE and ORDER BY
--    Active enterprise customers, most recently created first.
-- ---------------------------------------------------------------------
SELECT customer_number, customer_name, city, region, customer_type
FROM customers
WHERE customer_type = 'ENTERPRISE'
  AND status = 'ACTIVE'
ORDER BY created_at DESC;


-- ---------------------------------------------------------------------
-- 2. INNER JOIN across three tables
--    Every ticket with its customer and the affected service.
-- ---------------------------------------------------------------------
SELECT t.ticket_number,
       c.customer_name,
       s.service_name,
       t.category,
       t.priority,
       t.status
FROM trouble_tickets t
         INNER JOIN customers c ON c.customer_id = t.customer_id
         INNER JOIN telecom_services s ON s.service_id = t.service_id
ORDER BY t.created_date DESC;


-- ---------------------------------------------------------------------
-- 3. LEFT JOIN
--    Engineers including those who have never been assigned a ticket,
--    which an INNER JOIN would silently drop.
-- ---------------------------------------------------------------------
SELECT e.employee_code,
       e.engineer_name,
       e.specialization,
       COUNT(t.ticket_id) AS tickets_ever_assigned
FROM network_engineers e
         LEFT JOIN trouble_tickets t ON t.assigned_engineer_id = e.engineer_id
GROUP BY e.employee_code, e.engineer_name, e.specialization
ORDER BY tickets_ever_assigned DESC;


-- ---------------------------------------------------------------------
-- 4. GROUP BY with aggregate functions
--    Ticket counts by status.
-- ---------------------------------------------------------------------
SELECT status,
       COUNT(*)                AS ticket_count,
       MIN(created_date)       AS oldest,
       MAX(created_date)       AS newest
FROM trouble_tickets
GROUP BY status
ORDER BY ticket_count DESC;


-- ---------------------------------------------------------------------
-- 5. GROUP BY with HAVING
--    Categories that have produced more than one incident. HAVING is
--    required here because the filter is on the aggregate itself.
-- ---------------------------------------------------------------------
SELECT category,
       COUNT(*)                                                 AS incident_count,
       SUM(CASE WHEN priority = 'CRITICAL' THEN 1 ELSE 0 END)   AS critical_count
FROM trouble_tickets
GROUP BY category
HAVING COUNT(*) > 1
ORDER BY incident_count DESC;


-- ---------------------------------------------------------------------
-- 6. CASE expression
--    Bucket every ticket by how urgent it is in plain language.
-- ---------------------------------------------------------------------
SELECT ticket_number,
       priority,
       status,
       CASE
           WHEN status IN ('RESOLVED', 'CLOSED')      THEN 'Completed'
           WHEN priority = 'CRITICAL'                 THEN 'Drop everything'
           WHEN priority = 'HIGH'                     THEN 'Same shift'
           WHEN priority = 'MEDIUM'                   THEN 'Same day'
           ELSE                                            'Scheduled'
           END                                        AS handling_guidance
FROM trouble_tickets
ORDER BY FIELD(priority, 'CRITICAL', 'HIGH', 'MEDIUM', 'LOW');


-- ---------------------------------------------------------------------
-- 7. Scalar subquery in the SELECT list
--    Each customer with their open ticket count.
-- ---------------------------------------------------------------------
SELECT c.customer_number,
       c.customer_name,
       (SELECT COUNT(*)
        FROM trouble_tickets t
        WHERE t.customer_id = c.customer_id
          AND t.status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED')) AS open_tickets
FROM customers c
ORDER BY open_tickets DESC, c.customer_name;


-- ---------------------------------------------------------------------
-- 8. Subquery with IN
--    Customers who have ever raised a critical incident.
-- ---------------------------------------------------------------------
SELECT customer_number, customer_name, customer_type, city
FROM customers
WHERE customer_id IN (SELECT DISTINCT customer_id
                      FROM trouble_tickets
                      WHERE priority = 'CRITICAL');


-- ---------------------------------------------------------------------
-- 9. Correlated subquery with EXISTS
--    Services that currently have an unresolved ticket against them.
-- ---------------------------------------------------------------------
SELECT s.service_code, s.service_name, s.service_type
FROM telecom_services s
WHERE EXISTS (SELECT 1
              FROM trouble_tickets t
              WHERE t.service_id = s.service_id
                AND t.status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED'));


-- ---------------------------------------------------------------------
-- 10. Subquery in the FROM clause, comparing against an average
--     Engineers carrying a heavier load than the team average.
-- ---------------------------------------------------------------------
SELECT e.employee_code,
       e.engineer_name,
       e.active_ticket_count,
       team.average_load
FROM network_engineers e
         CROSS JOIN (SELECT ROUND(AVG(active_ticket_count), 2) AS average_load
                     FROM network_engineers) AS team
WHERE e.active_ticket_count > team.average_load
ORDER BY e.active_ticket_count DESC;


-- ---------------------------------------------------------------------
-- 11. Date functions
--     Tickets raised in the last seven days, grouped by day.
-- ---------------------------------------------------------------------
SELECT DATE(created_date)                       AS raised_on,
       DAYNAME(created_date)                    AS weekday,
       COUNT(*)                                 AS tickets_raised,
       DATEDIFF(CURDATE(), DATE(created_date))  AS days_ago
FROM trouble_tickets
WHERE created_date >= DATE_SUB(NOW(), INTERVAL 7 DAY)
GROUP BY DATE(created_date), DAYNAME(created_date)
ORDER BY raised_on DESC;


-- ---------------------------------------------------------------------
-- 12. Date arithmetic for SLA analysis
--     How long each completed ticket actually took, against its target.
-- ---------------------------------------------------------------------
SELECT t.ticket_number,
       t.priority,
       t.created_date,
       t.resolution_date,
       TIMESTAMPDIFF(MINUTE, t.created_date, t.resolution_date)  AS actual_minutes,
       sc.resolution_minutes                                     AS target_minutes,
       CASE
           WHEN t.resolution_date <= t.sla_deadline THEN 'MET'
           ELSE 'BREACHED'
           END                                                   AS sla_outcome
FROM trouble_tickets t
         INNER JOIN sla_configuration sc ON sc.priority = t.priority
WHERE t.resolution_date IS NOT NULL
ORDER BY actual_minutes DESC;


-- ---------------------------------------------------------------------
-- 13. Aggregate functions together
--     Resolution statistics per priority band.
-- ---------------------------------------------------------------------
SELECT priority,
       COUNT(*)                                                      AS resolved_tickets,
       ROUND(AVG(TIMESTAMPDIFF(MINUTE, created_date, resolution_date)), 1) AS avg_minutes,
       MIN(TIMESTAMPDIFF(MINUTE, created_date, resolution_date))     AS fastest_minutes,
       MAX(TIMESTAMPDIFF(MINUTE, created_date, resolution_date))     AS slowest_minutes,
       SUM(TIMESTAMPDIFF(MINUTE, created_date, resolution_date))     AS total_minutes
FROM trouble_tickets
WHERE resolution_date IS NOT NULL
GROUP BY priority
ORDER BY avg_minutes DESC;


-- ---------------------------------------------------------------------
-- 14. Regional incident report, joining through the customer
-- ---------------------------------------------------------------------
SELECT c.region,
       COUNT(t.ticket_id)                                        AS total_incidents,
       SUM(CASE WHEN t.priority = 'CRITICAL' THEN 1 ELSE 0 END)  AS critical,
       SUM(CASE WHEN t.status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED')
                    THEN 1 ELSE 0 END)                           AS still_open,
       ROUND(AVG(fn_resolution_hours(t.created_date, t.resolution_date)), 2) AS avg_hours
FROM customers c
         INNER JOIN trouble_tickets t ON t.customer_id = c.customer_id
GROUP BY c.region
ORDER BY total_incidents DESC;


-- ---------------------------------------------------------------------
-- 15. Querying the views
-- ---------------------------------------------------------------------
SELECT * FROM vw_manager_dashboard;

SELECT ticket_number, customer_name, priority, sla_status, minutes_remaining
FROM vw_open_tickets
ORDER BY priority_weight DESC, minutes_remaining ASC;

SELECT employee_code, engineer_name, specialization, currently_open, avg_resolution_hours
FROM vw_engineer_workload
ORDER BY currently_open DESC;

SELECT * FROM vw_sla_compliance ORDER BY priority;

SELECT * FROM vw_customer_repeat_incidents ORDER BY incident_count DESC;


-- ---------------------------------------------------------------------
-- 16. Calling the stored functions directly
-- ---------------------------------------------------------------------
SELECT fn_sla_deadline('CRITICAL', NOW())            AS critical_deadline,
       fn_sla_response_deadline('CRITICAL', NOW())   AS critical_response_due,
       fn_sla_deadline('LOW', NOW())                 AS low_deadline;

SELECT ticket_number,
       fn_sla_status(priority, created_date, sla_deadline, resolution_date, status) AS sla_state,
       fn_minutes_remaining(sla_deadline)                                           AS minutes_left
FROM trouble_tickets
WHERE status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED')
ORDER BY minutes_left;


-- ---------------------------------------------------------------------
-- 17. Calling the stored procedures
-- ---------------------------------------------------------------------
CALL sp_recommend_engineers('BROADBAND', 'SOUTH', 3);

CALL sp_ticket_volume_report(DATE_SUB(CURDATE(), INTERVAL 7 DAY), CURDATE());

CALL sp_manager_dashboard();

-- Transactional assignment. Assigns the first open critical ticket to
-- an available core network engineer.
SET @ticket := (SELECT ticket_id FROM trouble_tickets
                WHERE status = 'OPEN' AND priority = 'CRITICAL' LIMIT 1);
SET @engineer := (SELECT engineer_id FROM network_engineers
                  WHERE specialization = 'CORE_NETWORK' AND availability = 'AVAILABLE' LIMIT 1);
CALL sp_assign_engineer(@ticket, @engineer, 'sdesk1', @result, @message);
SELECT @result AS assignment_result, @message AS assignment_message;


-- ---------------------------------------------------------------------
-- 18. INSERT
--     Raise a ticket. sla_deadline is omitted on purpose so the BEFORE
--     INSERT trigger derives it from the priority band.
-- ---------------------------------------------------------------------
INSERT INTO trouble_tickets
    (ticket_number, customer_id, service_id, category, description,
     priority, severity, status, created_by)
VALUES ('TT-2026-009001',
        (SELECT customer_id FROM customers WHERE customer_number = 'CUST100118'),
        (SELECT service_id FROM telecom_services WHERE service_code = 'SVC500004'),
        'CALL_DROP', 'Demonstration ticket created by the SQL script',
        'MEDIUM', 'MINOR', 'OPEN', 'sql_demo');

SELECT ticket_number, priority, created_date, sla_response_deadline, sla_deadline
FROM trouble_tickets
WHERE ticket_number = 'TT-2026-009001';


-- ---------------------------------------------------------------------
-- 19. UPDATE
--     Raising the priority; the BEFORE UPDATE trigger recalculates both
--     deadlines to match the new band.
-- ---------------------------------------------------------------------
UPDATE trouble_tickets
SET priority = 'HIGH',
    severity = 'MAJOR'
WHERE ticket_number = 'TT-2026-009001';

SELECT ticket_number, priority, sla_response_deadline, sla_deadline
FROM trouble_tickets
WHERE ticket_number = 'TT-2026-009001';


-- ---------------------------------------------------------------------
-- 20. Batch UPDATE driven by a subquery
--     Flag every open ticket already past its deadline as breached.
-- ---------------------------------------------------------------------
UPDATE trouble_tickets
SET sla_status = 'BREACHED'
WHERE status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED')
  AND sla_deadline < NOW();


-- ---------------------------------------------------------------------
-- 21. DELETE
--     Remove the demonstration ticket. The cascade on
--     ticket_status_history clears its history rows automatically.
-- ---------------------------------------------------------------------
DELETE FROM trouble_tickets
WHERE ticket_number = 'TT-2026-009001';


-- ---------------------------------------------------------------------
-- 22. Verifying the constraints actually bite
--     Each of these must fail. Run them one at a time.
-- ---------------------------------------------------------------------
-- Rejected by chk_tickets_priority:
-- INSERT INTO trouble_tickets (ticket_number, customer_id, service_id, category,
--     description, priority, severity, status, created_by)
-- VALUES ('TT-BAD-0001', 1, 1, 'OTHER', 'Invalid priority', 'URGENT', 'MINOR', 'OPEN', 'test');

-- Rejected by uq_tickets_number:
-- INSERT INTO trouble_tickets (ticket_number, customer_id, service_id, category,
--     description, priority, severity, status, created_by)
-- VALUES ('TT-2026-004521', 1, 1, 'OTHER', 'Duplicate number', 'LOW', 'INFO', 'OPEN', 'test');

-- Rejected by fk_tickets_customer:
-- INSERT INTO trouble_tickets (ticket_number, customer_id, service_id, category,
--     description, priority, severity, status, created_by)
-- VALUES ('TT-BAD-0002', 99999, 1, 'OTHER', 'No such customer', 'LOW', 'INFO', 'OPEN', 'test');

-- Rejected by chk_feedback_rating:
-- INSERT INTO feedback (ticket_id, customer_id, rating) VALUES (1, 1, 9);

-- Rejected by fk_services_customer ON DELETE RESTRICT:
-- DELETE FROM customers WHERE customer_number = 'CUST100245';
