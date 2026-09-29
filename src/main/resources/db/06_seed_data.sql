-- =====================================================================
-- TSATMS - 06 - Reference and demonstration data
-- ---------------------------------------------------------------------
-- The SLA bands and the engineer roster are taken directly from
-- sections 7 and 8 of the case study. The customers, services and
-- tickets are representative data so that the views, procedures and
-- reports return something meaningful from the outset.
--
-- Login credentials are deliberately not set here. Password hashing
-- lives in the security phase, so every account is seeded with a
-- sentinel hash and PENDING_ACTIVATION status; the security phase
-- replaces those with real salted hashes.
-- =====================================================================

USE `${db.schema}`;

SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE login_history;
TRUNCATE TABLE audit_log;
TRUNCATE TABLE feedback;
TRUNCATE TABLE notifications;
TRUNCATE TABLE network_events;
TRUNCATE TABLE escalation_history;
TRUNCATE TABLE ticket_status_history;
TRUNCATE TABLE trouble_tickets;
TRUNCATE TABLE sla_configuration;
TRUNCATE TABLE network_engineers;
TRUNCATE TABLE telecom_services;
TRUNCATE TABLE customers;
TRUNCATE TABLE users;
SET FOREIGN_KEY_CHECKS = 1;


-- ---------------------------------------------------------------------
-- SLA configuration, exactly as tabulated in section 8.
--   CRITICAL   15 min response    2 hours resolution
--   HIGH       30 min response    4 hours resolution
--   MEDIUM      2 hours response 12 hours resolution
--   LOW         8 hours response 48 hours resolution
-- ---------------------------------------------------------------------
INSERT INTO sla_configuration
    (priority, response_minutes, resolution_minutes, at_risk_threshold_pct, description)
VALUES
    ('CRITICAL', 15, 120, 80, 'Service down for an enterprise or a whole site'),
    ('HIGH', 30, 240, 80, 'Severe degradation affecting many subscribers'),
    ('MEDIUM', 120, 720, 80, 'Degradation affecting a single subscriber'),
    ('LOW', 480, 2880, 80, 'Query or cosmetic issue with no service impact');


-- ---------------------------------------------------------------------
-- Login accounts. PENDING_PHASE5 is a sentinel replaced by the security
-- phase with a real PBKDF2 hash and per-user salt.
-- ---------------------------------------------------------------------
INSERT INTO users (username, password_hash, password_salt, full_name, email, role, account_status)
VALUES
    ('sdesk1',  'PENDING_PHASE5', 'PENDING_PHASE5', 'Priya Sharma',   'priya.sharma@telecom.example',   'SERVICE_DESK',     'PENDING_ACTIVATION'),
    ('sdesk2',  'PENDING_PHASE5', 'PENDING_PHASE5', 'Imran Qureshi',  'imran.qureshi@telecom.example',  'SERVICE_DESK',     'PENDING_ACTIVATION'),
    ('nmgr1',   'PENDING_PHASE5', 'PENDING_PHASE5', 'Rajesh Kumar',   'rajesh.kumar@telecom.example',   'NETWORK_MANAGER',  'PENDING_ACTIVATION'),
    ('eng1008', 'PENDING_PHASE5', 'PENDING_PHASE5', 'Arun Menon',     'arun.menon@telecom.example',     'NETWORK_ENGINEER', 'PENDING_ACTIVATION'),
    ('eng1015', 'PENDING_PHASE5', 'PENDING_PHASE5', 'Neha Gupta',     'neha.gupta@telecom.example',     'NETWORK_ENGINEER', 'PENDING_ACTIVATION'),
    ('eng1021', 'PENDING_PHASE5', 'PENDING_PHASE5', 'Vikram Rao',     'vikram.rao@telecom.example',     'NETWORK_ENGINEER', 'PENDING_ACTIVATION'),
    ('eng1030', 'PENDING_PHASE5', 'PENDING_PHASE5', 'Sanjay Iyer',    'sanjay.iyer@telecom.example',    'NETWORK_ENGINEER', 'PENDING_ACTIVATION'),
    ('eng1042', 'PENDING_PHASE5', 'PENDING_PHASE5', 'Meera Nair',     'meera.nair@telecom.example',     'NETWORK_ENGINEER', 'PENDING_ACTIVATION'),
    ('eng1055', 'PENDING_PHASE5', 'PENDING_PHASE5', 'Farhan Ali',     'farhan.ali@telecom.example',     'NETWORK_ENGINEER', 'PENDING_ACTIVATION'),
    ('cust100245', 'PENDING_PHASE5', 'PENDING_PHASE5', 'Zenith Logistics',  'ops@zenithlogistics.example',  'CUSTOMER', 'PENDING_ACTIVATION'),
    ('cust100118', 'PENDING_PHASE5', 'PENDING_PHASE5', 'Anita Desai',       'anita.desai@mail.example',     'CUSTOMER', 'PENDING_ACTIVATION'),
    ('cust100376', 'PENDING_PHASE5', 'PENDING_PHASE5', 'Northline Retail',  'it@northlineretail.example',   'CUSTOMER', 'PENDING_ACTIVATION'),
    ('cust100452', 'PENDING_PHASE5', 'PENDING_PHASE5', 'Ravi Krishnan',     'ravi.krishnan@mail.example',   'CUSTOMER', 'PENDING_ACTIVATION'),
    ('cust100513', 'PENDING_PHASE5', 'PENDING_PHASE5', 'Orbit Softwares',   'admin@orbitsoft.example',      'CUSTOMER', 'PENDING_ACTIVATION'),
    ('cust100629', 'PENDING_PHASE5', 'PENDING_PHASE5', 'Ganges Textiles',   'network@gangestex.example',    'CUSTOMER', 'PENDING_ACTIVATION'),
    ('cust100744', 'PENDING_PHASE5', 'PENDING_PHASE5', 'Sunita Patil',      'sunita.patil@mail.example',    'CUSTOMER', 'PENDING_ACTIVATION'),
    ('cust100851', 'PENDING_PHASE5', 'PENDING_PHASE5', 'Apex Financial',    'noc@apexfinancial.example',    'CUSTOMER', 'PENDING_ACTIVATION');


-- ---------------------------------------------------------------------
-- Customers
-- ---------------------------------------------------------------------
INSERT INTO customers
    (customer_number, customer_name, email, mobile_number, customer_type, city, region, status, user_id)
VALUES
    ('CUST100245', 'Zenith Logistics Pvt Ltd', 'ops@zenithlogistics.example', '9820011245', 'ENTERPRISE', 'Mumbai',    'WEST',    'ACTIVE', (SELECT user_id FROM users WHERE username = 'cust100245')),
    ('CUST100118', 'Anita Desai',              'anita.desai@mail.example',    '9822100118', 'CONSUMER',   'Pune',      'WEST',    'ACTIVE', (SELECT user_id FROM users WHERE username = 'cust100118')),
    ('CUST100376', 'Northline Retail',         'it@northlineretail.example',  '9811200376', 'SME',        'Delhi',     'NORTH',   'ACTIVE', (SELECT user_id FROM users WHERE username = 'cust100376')),
    ('CUST100452', 'Ravi Krishnan',            'ravi.krishnan@mail.example',  '9840300452', 'CONSUMER',   'Chennai',   'SOUTH',   'ACTIVE', (SELECT user_id FROM users WHERE username = 'cust100452')),
    ('CUST100513', 'Orbit Softwares',          'admin@orbitsoft.example',     '9845400513', 'SME',        'Bengaluru', 'SOUTH',   'ACTIVE', (SELECT user_id FROM users WHERE username = 'cust100513')),
    ('CUST100629', 'Ganges Textiles',          'network@gangestex.example',   '9830500629', 'ENTERPRISE', 'Kolkata',   'EAST',    'ACTIVE', (SELECT user_id FROM users WHERE username = 'cust100629')),
    ('CUST100744', 'Sunita Patil',             'sunita.patil@mail.example',   '9823600744', 'CONSUMER',   'Nagpur',    'CENTRAL', 'ACTIVE', (SELECT user_id FROM users WHERE username = 'cust100744')),
    ('CUST100851', 'Apex Financial Services',  'noc@apexfinancial.example',   '9848700851', 'ENTERPRISE', 'Hyderabad', 'SOUTH',   'ACTIVE', (SELECT user_id FROM users WHERE username = 'cust100851'));


-- ---------------------------------------------------------------------
-- Subscribed services
-- ---------------------------------------------------------------------
INSERT INTO telecom_services
    (service_code, service_name, service_type, customer_id, activation_date, service_status)
VALUES
    ('SVC500001', 'Zenith HQ Enterprise Link',   'ENTERPRISE_CONNECTIVITY', (SELECT customer_id FROM customers WHERE customer_number = 'CUST100245'), '2024-04-15', 'ACTIVE'),
    ('SVC500002', 'Zenith Site-to-Site VPN',     'VPN',                     (SELECT customer_id FROM customers WHERE customer_number = 'CUST100245'), '2024-06-01', 'ACTIVE'),
    ('SVC500003', 'Zenith Cloud Interconnect',   'CLOUD_CONNECTIVITY',      (SELECT customer_id FROM customers WHERE customer_number = 'CUST100245'), '2025-01-20', 'ACTIVE'),
    ('SVC500004', 'Anita Postpaid Mobile',       'MOBILE',                  (SELECT customer_id FROM customers WHERE customer_number = 'CUST100118'), '2023-09-10', 'ACTIVE'),
    ('SVC500005', 'Anita Home Fibre 200',        'BROADBAND',               (SELECT customer_id FROM customers WHERE customer_number = 'CUST100118'), '2024-02-05', 'ACTIVE'),
    ('SVC500006', 'Northline Store Broadband',   'BROADBAND',               (SELECT customer_id FROM customers WHERE customer_number = 'CUST100376'), '2024-08-22', 'ACTIVE'),
    ('SVC500007', 'Northline Branch VPN',        'VPN',                     (SELECT customer_id FROM customers WHERE customer_number = 'CUST100376'), '2025-03-11', 'ACTIVE'),
    ('SVC500008', 'Ravi Prepaid Mobile',         'MOBILE',                  (SELECT customer_id FROM customers WHERE customer_number = 'CUST100452'), '2025-05-30', 'ACTIVE'),
    ('SVC500009', 'Orbit Office Fibre 500',      'BROADBAND',               (SELECT customer_id FROM customers WHERE customer_number = 'CUST100513'), '2024-11-18', 'ACTIVE'),
    ('SVC500010', 'Orbit Cloud Interconnect',    'CLOUD_CONNECTIVITY',      (SELECT customer_id FROM customers WHERE customer_number = 'CUST100513'), '2025-07-02', 'ACTIVE'),
    ('SVC500011', 'Ganges Mill Enterprise Link', 'ENTERPRISE_CONNECTIVITY', (SELECT customer_id FROM customers WHERE customer_number = 'CUST100629'), '2023-12-01', 'ACTIVE'),
    ('SVC500012', 'Sunita Home Fibre 100',       'BROADBAND',               (SELECT customer_id FROM customers WHERE customer_number = 'CUST100744'), '2025-02-14', 'ACTIVE'),
    ('SVC500013', 'Apex Trading Floor Link',     'ENTERPRISE_CONNECTIVITY', (SELECT customer_id FROM customers WHERE customer_number = 'CUST100851'), '2024-01-08', 'ACTIVE'),
    ('SVC500014', 'Apex DR Site VPN',            'VPN',                     (SELECT customer_id FROM customers WHERE customer_number = 'CUST100851'), '2024-01-08', 'ACTIVE');


-- ---------------------------------------------------------------------
-- Engineer roster. The first four are the worked example in section 7.
-- ---------------------------------------------------------------------
INSERT INTO network_engineers
    (employee_code, engineer_name, email, mobile_number, specialization, region,
     experience_years, availability, active_ticket_count, max_ticket_capacity, user_id)
VALUES
    ('ENG1008', 'Arun Menon',  'arun.menon@telecom.example',  '9820001008', 'CORE_NETWORK',        'WEST',    9,  'AVAILABLE', 0, 8,  (SELECT user_id FROM users WHERE username = 'eng1008')),
    ('ENG1015', 'Neha Gupta',  'neha.gupta@telecom.example',  '9811001015', 'RAN',                 'NORTH',   7,  'AVAILABLE', 0, 8,  (SELECT user_id FROM users WHERE username = 'eng1015')),
    ('ENG1021', 'Vikram Rao',  'vikram.rao@telecom.example',  '9840001021', 'BROADBAND',           'SOUTH',   6,  'AVAILABLE', 0, 8,  (SELECT user_id FROM users WHERE username = 'eng1021')),
    ('ENG1030', 'Sanjay Iyer', 'sanjay.iyer@telecom.example', '9820001030', 'IP_NETWORK',          'WEST',    10, 'AVAILABLE', 0, 8,  (SELECT user_id FROM users WHERE username = 'eng1030')),
    ('ENG1042', 'Meera Nair',  'meera.nair@telecom.example',  '9823001042', 'TRANSMISSION',        'CENTRAL', 5,  'AVAILABLE', 0, 8,  (SELECT user_id FROM users WHERE username = 'eng1042')),
    ('ENG1055', 'Farhan Ali',  'farhan.ali@telecom.example',  '9830001055', 'ENTERPRISE_SERVICES', 'EAST',    8,  'AVAILABLE', 0, 8,  (SELECT user_id FROM users WHERE username = 'eng1055'));


-- ---------------------------------------------------------------------
-- Trouble tickets.
--
-- Timestamps are expressed relative to NOW() so the SLA states stay
-- realistic no matter when the seed is loaded. sla_deadline is left out
-- on purpose: the BEFORE INSERT trigger derives it from the priority,
-- which also proves the trigger works.
-- ---------------------------------------------------------------------
INSERT INTO trouble_tickets
    (ticket_number, customer_id, service_id, category, description, priority, severity,
     status, assigned_engineer_id, created_date, resolution_date, resolution_code,
     root_cause, resolution, auto_created, created_by)
VALUES
    -- Open, unassigned, raised minutes ago
    ('TT-2026-004521', (SELECT customer_id FROM customers WHERE customer_number = 'CUST100245'),
     (SELECT service_id FROM telecom_services WHERE service_code = 'SVC500001'),
     'NETWORK_OUTAGE', 'Complete loss of connectivity on the HQ enterprise link', 'CRITICAL', 'CRITICAL',
     'OPEN', NULL, DATE_SUB(NOW(), INTERVAL 20 MINUTE), NULL, NULL, NULL, NULL, FALSE, 'cust100245'),

    ('TT-2026-004522', (SELECT customer_id FROM customers WHERE customer_number = 'CUST100118'),
     (SELECT service_id FROM telecom_services WHERE service_code = 'SVC500004'),
     'CALL_DROP', 'Frequent call drops in the Kothrud area during evenings', 'MEDIUM', 'MINOR',
     'OPEN', NULL, DATE_SUB(NOW(), INTERVAL 3 HOUR), NULL, NULL, NULL, NULL, FALSE, 'cust100118'),

    -- Assigned and in progress
    ('TT-2026-004523', (SELECT customer_id FROM customers WHERE customer_number = 'CUST100851'),
     (SELECT service_id FROM telecom_services WHERE service_code = 'SVC500013'),
     'ENTERPRISE_LINK', 'Trading floor link flapping every few minutes', 'CRITICAL', 'MAJOR',
     'IN_PROGRESS', (SELECT engineer_id FROM network_engineers WHERE employee_code = 'ENG1055'),
     DATE_SUB(NOW(), INTERVAL 90 MINUTE), NULL, NULL, NULL, NULL, FALSE, 'sdesk1'),

    ('TT-2026-004524', (SELECT customer_id FROM customers WHERE customer_number = 'CUST100513'),
     (SELECT service_id FROM telecom_services WHERE service_code = 'SVC500009'),
     'SLOW_DATA', 'Throughput far below the 500 Mbps subscribed rate', 'HIGH', 'MAJOR',
     'IN_PROGRESS', (SELECT engineer_id FROM network_engineers WHERE employee_code = 'ENG1021'),
     DATE_SUB(NOW(), INTERVAL 200 MINUTE), NULL, NULL, NULL, NULL, FALSE, 'sdesk1'),

    ('TT-2026-004525', (SELECT customer_id FROM customers WHERE customer_number = 'CUST100376'),
     (SELECT service_id FROM telecom_services WHERE service_code = 'SVC500006'),
     'BROADBAND', 'Store broadband down since the morning', 'HIGH', 'MAJOR',
     'ASSIGNED', (SELECT engineer_id FROM network_engineers WHERE employee_code = 'ENG1021'),
     DATE_SUB(NOW(), INTERVAL 45 MINUTE), NULL, NULL, NULL, NULL, FALSE, 'sdesk2'),

    -- Escalated, past deadline
    ('TT-2026-004526', (SELECT customer_id FROM customers WHERE customer_number = 'CUST100629'),
     (SELECT service_id FROM telecom_services WHERE service_code = 'SVC500011'),
     'NO_CONNECTIVITY', 'Mill site unreachable, suspected fibre damage on the access route', 'CRITICAL', 'CRITICAL',
     'ESCALATED', (SELECT engineer_id FROM network_engineers WHERE employee_code = 'ENG1055'),
     DATE_SUB(NOW(), INTERVAL 5 HOUR), NULL, NULL, NULL, NULL, FALSE, 'sdesk1'),

    -- Waiting on the customer
    ('TT-2026-004527', (SELECT customer_id FROM customers WHERE customer_number = 'CUST100744'),
     (SELECT service_id FROM telecom_services WHERE service_code = 'SVC500012'),
     'BROADBAND', 'Intermittent disconnections reported at the home connection', 'MEDIUM', 'MINOR',
     'PENDING_CUSTOMER', (SELECT engineer_id FROM network_engineers WHERE employee_code = 'ENG1021'),
     DATE_SUB(NOW(), INTERVAL 8 HOUR), NULL, NULL, NULL, NULL, FALSE, 'sdesk2'),

    -- Resolved within SLA
    ('TT-2026-004510', (SELECT customer_id FROM customers WHERE customer_number = 'CUST100245'),
     (SELECT service_id FROM telecom_services WHERE service_code = 'SVC500002'),
     'NO_CONNECTIVITY', 'Site-to-site VPN tunnel down after a scheduled change', 'HIGH', 'MAJOR',
     'RESOLVED', (SELECT engineer_id FROM network_engineers WHERE employee_code = 'ENG1030'),
     DATE_SUB(NOW(), INTERVAL 30 HOUR), DATE_SUB(NOW(), INTERVAL 28 HOUR), 'CONFIGURATION_ERROR',
     'Tunnel policy mismatch introduced during the change window',
     'Restored the previous crypto policy and re-established the tunnel', FALSE, 'sdesk1'),

    ('TT-2026-004511', (SELECT customer_id FROM customers WHERE customer_number = 'CUST100452'),
     (SELECT service_id FROM telecom_services WHERE service_code = 'SVC500008'),
     'SIM_ISSUE', 'SIM not registering on the network after a handset change', 'MEDIUM', 'MINOR',
     'CLOSED', (SELECT engineer_id FROM network_engineers WHERE employee_code = 'ENG1008'),
     DATE_SUB(NOW(), INTERVAL 52 HOUR), DATE_SUB(NOW(), INTERVAL 48 HOUR), 'CUSTOMER_DEVICE',
     'Handset was not provisioned for the VoLTE profile',
     'Re-provisioned the subscriber profile and confirmed registration', FALSE, 'sdesk2'),

    -- Resolved late, an SLA breach
    ('TT-2026-004512', (SELECT customer_id FROM customers WHERE customer_number = 'CUST100851'),
     (SELECT service_id FROM telecom_services WHERE service_code = 'SVC500014'),
     'ENTERPRISE_LINK', 'DR site VPN unavailable during the failover rehearsal', 'CRITICAL', 'CRITICAL',
     'CLOSED', (SELECT engineer_id FROM network_engineers WHERE employee_code = 'ENG1030'),
     DATE_SUB(NOW(), INTERVAL 76 HOUR), DATE_SUB(NOW(), INTERVAL 68 HOUR), 'HARDWARE_FAILURE',
     'Line card failure on the aggregation router',
     'Replaced the faulty line card and restored the DR tunnel', FALSE, 'sdesk1'),

    ('TT-2026-004513', (SELECT customer_id FROM customers WHERE customer_number = 'CUST100118'),
     (SELECT service_id FROM telecom_services WHERE service_code = 'SVC500005'),
     'SLOW_DATA', 'Evening speeds consistently below the subscribed rate', 'MEDIUM', 'MINOR',
     'RESOLVED', (SELECT engineer_id FROM network_engineers WHERE employee_code = 'ENG1021'),
     DATE_SUB(NOW(), INTERVAL 100 HOUR), DATE_SUB(NOW(), INTERVAL 82 HOUR), 'NETWORK_CONGESTION',
     'Oversubscription on the serving OLT during peak hours',
     'Rebalanced subscribers across the OLT uplinks', FALSE, 'sdesk2'),

    ('TT-2026-004514', (SELECT customer_id FROM customers WHERE customer_number = 'CUST100376'),
     (SELECT service_id FROM telecom_services WHERE service_code = 'SVC500007'),
     'ROAMING', 'Branch VPN unreachable from the roaming partner network', 'LOW', 'WARNING',
     'CLOSED', (SELECT engineer_id FROM network_engineers WHERE employee_code = 'ENG1008'),
     DATE_SUB(NOW(), INTERVAL 120 HOUR), DATE_SUB(NOW(), INTERVAL 100 HOUR), 'CONFIGURATION_ERROR',
     'Missing route advertisement towards the partner',
     'Added the route policy and verified reachability', FALSE, 'sdesk1'),

    -- Automatically raised from a network event
    ('TT-2026-004528', (SELECT customer_id FROM customers WHERE customer_number = 'CUST100245'),
     (SELECT service_id FROM telecom_services WHERE service_code = 'SVC500003'),
     'NETWORK_OUTAGE', 'Auto-raised from network event: LINK_DOWN on MUM-RAN-045', 'CRITICAL', 'CRITICAL',
     'ASSIGNED', (SELECT engineer_id FROM network_engineers WHERE employee_code = 'ENG1008'),
     DATE_SUB(NOW(), INTERVAL 35 MINUTE), NULL, NULL, NULL, NULL, TRUE, 'SYSTEM'),

    ('TT-2026-004529', (SELECT customer_id FROM customers WHERE customer_number = 'CUST100513'),
     (SELECT service_id FROM telecom_services WHERE service_code = 'SVC500010'),
     'BILLING', 'Query on the cloud interconnect usage charges for last month', 'LOW', 'INFO',
     'OPEN', NULL, DATE_SUB(NOW(), INTERVAL 26 HOUR), NULL, NULL, NULL, NULL, FALSE, 'cust100513'),

    ('TT-2026-004530', (SELECT customer_id FROM customers WHERE customer_number = 'CUST100629'),
     (SELECT service_id FROM telecom_services WHERE service_code = 'SVC500011'),
     'SLOW_DATA', 'Degraded throughput on the mill site link since the storm', 'HIGH', 'MAJOR',
     'CANCELLED', NULL, DATE_SUB(NOW(), INTERVAL 60 HOUR), NULL, NULL, NULL, NULL, FALSE, 'sdesk2');


-- ---------------------------------------------------------------------
-- Network events. The first mirrors the worked example in section 11.
-- ---------------------------------------------------------------------
INSERT INTO network_events
    (event_reference, network_node, event_type, severity, event_time, event_status, region, details, ticket_id, processed_date)
VALUES
    ('NE-884521', 'MUM-RAN-045', 'LINK_DOWN',        'CRITICAL', DATE_SUB(NOW(), INTERVAL 36 MINUTE), 'TICKET_CREATED', 'WEST',    'Uplink towards the aggregation ring lost',
     (SELECT ticket_id FROM trouble_tickets WHERE ticket_number = 'TT-2026-004528'), DATE_SUB(NOW(), INTERVAL 35 MINUTE)),
    ('NE-884522', 'MUM-RAN-045', 'LINK_UP',          'INFO',     DATE_SUB(NOW(), INTERVAL 30 MINUTE), 'IGNORED',        'WEST',    'Uplink restored briefly then flapped', NULL, DATE_SUB(NOW(), INTERVAL 30 MINUTE)),
    ('NE-884523', 'BLR-CORE-012','HIGH_LATENCY',     'MAJOR',    DATE_SUB(NOW(), INTERVAL 2 HOUR),    'RECEIVED',       'SOUTH',   'Round trip time above threshold on the core link', NULL, NULL),
    ('NE-884524', 'DEL-AGG-201', 'PACKET_LOSS',      'MINOR',    DATE_SUB(NOW(), INTERVAL 4 HOUR),    'RECEIVED',       'NORTH',   'Sustained packet loss of two percent', NULL, NULL),
    ('NE-884525', 'KOL-EDGE-078','NODE_UNREACHABLE', 'CRITICAL', DATE_SUB(NOW(), INTERVAL 6 HOUR),    'RECEIVED',       'EAST',    'Node not responding to management polls', NULL, NULL),
    ('NE-884526', 'NAG-BBD-330', 'HEARTBEAT',        'INFO',     DATE_SUB(NOW(), INTERVAL 10 MINUTE), 'IGNORED',        'CENTRAL', 'Routine keepalive', NULL, DATE_SUB(NOW(), INTERVAL 10 MINUTE));


-- ---------------------------------------------------------------------
-- Status history for the tickets that have moved beyond OPEN.
-- ---------------------------------------------------------------------
INSERT INTO ticket_status_history (ticket_id, old_status, new_status, changed_by, changed_date, remarks)
SELECT t.ticket_id, NULL, 'OPEN', t.created_by, t.created_date, 'Ticket raised'
FROM trouble_tickets t;

INSERT INTO ticket_status_history (ticket_id, old_status, new_status, changed_by, changed_date, remarks)
SELECT t.ticket_id, 'OPEN', 'ASSIGNED', 'sdesk1',
       DATE_ADD(t.created_date, INTERVAL 5 MINUTE),
       CONCAT('Assigned to ', e.employee_code)
FROM trouble_tickets t
         INNER JOIN network_engineers e ON e.engineer_id = t.assigned_engineer_id
WHERE t.assigned_engineer_id IS NOT NULL;

INSERT INTO ticket_status_history (ticket_id, old_status, new_status, changed_by, changed_date, remarks)
SELECT t.ticket_id, 'ASSIGNED', 'RESOLVED', 'engineer', t.resolution_date, t.resolution_code
FROM trouble_tickets t
WHERE t.resolution_date IS NOT NULL;


-- ---------------------------------------------------------------------
-- Escalation history for the one escalated ticket.
-- ---------------------------------------------------------------------
INSERT INTO escalation_history
    (ticket_id, from_level, to_level, reason, escalation_date, escalated_by, auto_escalated)
VALUES
    ((SELECT ticket_id FROM trouble_tickets WHERE ticket_number = 'TT-2026-004526'),
     'ENGINEER', 'TEAM_LEAD', 'Resolution SLA breached with no root cause identified',
     DATE_SUB(NOW(), INTERVAL 3 HOUR), 'SLA_MONITOR', TRUE);

UPDATE trouble_tickets
SET escalation_level = 'TEAM_LEAD'
WHERE ticket_number = 'TT-2026-004526';


-- ---------------------------------------------------------------------
-- Customer feedback on completed tickets.
-- ---------------------------------------------------------------------
INSERT INTO feedback (ticket_id, customer_id, rating, comments, submitted_date)
VALUES
    ((SELECT ticket_id FROM trouble_tickets WHERE ticket_number = 'TT-2026-004510'),
     (SELECT customer_id FROM customers WHERE customer_number = 'CUST100245'), 5,
     'Restored quickly and communication was clear throughout', DATE_SUB(NOW(), INTERVAL 27 HOUR)),
    ((SELECT ticket_id FROM trouble_tickets WHERE ticket_number = 'TT-2026-004511'),
     (SELECT customer_id FROM customers WHERE customer_number = 'CUST100452'), 4,
     'Resolved, though it took a couple of follow up calls', DATE_SUB(NOW(), INTERVAL 47 HOUR)),
    ((SELECT ticket_id FROM trouble_tickets WHERE ticket_number = 'TT-2026-004512'),
     (SELECT customer_id FROM customers WHERE customer_number = 'CUST100851'), 2,
     'Took far too long for a critical trading floor outage', DATE_SUB(NOW(), INTERVAL 67 HOUR)),
    ((SELECT ticket_id FROM trouble_tickets WHERE ticket_number = 'TT-2026-004513'),
     (SELECT customer_id FROM customers WHERE customer_number = 'CUST100118'), 3,
     'Speeds are better now but the wait was long', DATE_SUB(NOW(), INTERVAL 80 HOUR));


-- ---------------------------------------------------------------------
-- Rebuild the engineer workload aggregate from the tickets just loaded,
-- so the seeded counts cannot drift from reality.
-- ---------------------------------------------------------------------
UPDATE network_engineers e
SET e.active_ticket_count = (
    SELECT COUNT(*)
    FROM trouble_tickets t
    WHERE t.assigned_engineer_id = e.engineer_id
      AND t.status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED'));

UPDATE network_engineers
SET availability = 'BUSY'
WHERE active_ticket_count >= max_ticket_capacity;

-- One engineer off shift, so the assignment engine has someone to skip.
UPDATE network_engineers SET availability = 'ON_LEAVE' WHERE employee_code = 'ENG1042';
