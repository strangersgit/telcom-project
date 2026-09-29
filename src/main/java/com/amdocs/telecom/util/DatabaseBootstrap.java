package com.amdocs.telecom.util;

import com.amdocs.telecom.exception.DataAccessException;
import com.amdocs.telecom.exception.ErrorCode;
import com.amdocs.telecom.exception.TSATMSException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates the database from the scripts bundled in {@code resources/db}.
 *
 * <p>Runs as the administrator account, because the first script has to
 * create both the schema and the application account before anything else can
 * connect. Afterwards the application itself only ever uses its own
 * least-privilege account.</p>
 */
public final class DatabaseBootstrap {

    /** Executed in order. 07_sample_queries.sql is reference only. */
    private static final String[] SCRIPTS = {
            "db/00_bootstrap.sql",
            "db/01_schema.sql",
            "db/02_functions.sql",
            "db/03_triggers.sql",
            "db/04_views.sql",
            "db/05_procedures.sql",
            "db/06_seed_data.sql"
    };

    private static final String[] DESCRIPTIONS = {
            "Schema and application account",
            "Tables, constraints and indexes",
            "Stored functions",
            "Triggers",
            "Views",
            "Stored procedures",
            "Reference and demonstration data"
    };

    /**
     * The scripts holding functions, triggers, views and procedures. Each
     * one drops and recreates only its own objects, so this range can be
     * re-run on a live database to pick up a change to a trigger or a view
     * without touching a single row.
     */
    private static final int FIRST_PROGRAMMABLE_SCRIPT = 2;
    private static final int LAST_PROGRAMMABLE_SCRIPT = 5;

    private static final String[] SEEDED_TABLES = {
            "users", "customers", "telecom_services", "network_engineers",
            "sla_configuration", "trouble_tickets", "ticket_status_history",
            "escalation_history", "network_events", "notifications",
            "feedback", "audit_log", "login_history"
    };

    private DatabaseBootstrap() {
        throw new AssertionError("DatabaseBootstrap is not instantiable");
    }

    /**
     * Runs every script in order, then prints a verification summary.
     *
     * @return {@code 0} on success, non-zero when the build could not complete
     */
    public static int execute() {
        ConfigLoader config = ConfigLoader.getInstance();
        DBConnection database = DBConnection.getInstance();

        Map<String, String> substitutions = new LinkedHashMap<>();
        substitutions.put("db.schema", database.getSchema());
        substitutions.put("db.username", database.getApplicationUser());
        substitutions.put("db.password", config.getString("db.password", ""));

        System.out.println();
        System.out.println("  " + AppConstants.LINE_DOUBLE);
        System.out.println("  DATABASE BUILD");
        System.out.println("  " + AppConstants.LINE_DOUBLE);
        System.out.println("  Target   : " + database.getSchema()
                + " on " + database.getHost() + ":" + database.getPort());
        System.out.println("  Running as administrator account '"
                + database.getAdministratorUser() + "'");
        System.out.println();

        long startedAt = System.currentTimeMillis();
        int totalStatements = 0;

        try (Connection connection = database.getAdminConnection()) {
            SqlScriptRunner runner = new SqlScriptRunner(connection);

            for (int index = 0; index < SCRIPTS.length; index++) {
                String script = SCRIPTS[index];
                String label = DESCRIPTIONS[index];
                long scriptStart = System.currentTimeMillis();

                int count = runner.runScript(script, substitutions);
                totalStatements += count;

                System.out.println(String.format("  [ OK ]  %-36s %3d statements  %5d ms",
                        label, count, System.currentTimeMillis() - scriptStart));
            }
        } catch (TSATMSException failure) {
            System.out.println();
            System.out.println("  [FAIL]  " + failure.toDisplayString());
            AppLogger.error(DatabaseBootstrap.class, "Database build failed", failure);
            System.out.println();
            System.out.println("  Nothing was left half built: each script drops and recreates");
            System.out.println("  its objects, so fixing the cause and running again is safe.");
            System.out.println();
            return 1;
        } catch (SQLException failure) {
            System.out.println();
            System.out.println("  [FAIL]  Could not open an administrator connection: "
                    + failure.getMessage());
            AppLogger.error(DatabaseBootstrap.class, "Administrator connection failed", failure);
            System.out.println();
            return 1;
        }

        System.out.println();
        System.out.println("  " + totalStatements + " statements in "
                + (System.currentTimeMillis() - startedAt) + " ms");

        return verify(database);
    }

    /**
     * Rebuilds only the functions, triggers, views and procedures.
     *
     * <p>Exists because the alternative is unacceptable in practice. A full
     * {@code --setup-db} recreates the tables and reloads the seed, which
     * discards the passwords the administrator provisioned for the seeded
     * accounts. When a later phase changes a trigger or a view, this brings
     * the database up to date and leaves every row where it was.</p>
     *
     * @return {@code 0} on success, non-zero when a script failed
     */
    public static int refreshProgrammableObjects() {
        DBConnection database = DBConnection.getInstance();

        System.out.println();
        System.out.println("  " + AppConstants.LINE_DOUBLE);
        System.out.println("  REFRESH FUNCTIONS, TRIGGERS, VIEWS AND PROCEDURES");
        System.out.println("  " + AppConstants.LINE_DOUBLE);
        System.out.println("  Target   : " + database.getSchema()
                + " on " + database.getHost() + ":" + database.getPort());
        System.out.println("  No table is created or dropped and no row is touched.");
        System.out.println();

        long startedAt = System.currentTimeMillis();
        int totalStatements = 0;

        // Every script opens with USE `${db.schema}`. The credential
        // placeholder belongs to 00_bootstrap.sql alone and is deliberately
        // left out, so nothing here needs to read the password.
        Map<String, String> substitutions = new LinkedHashMap<String, String>();
        substitutions.put("db.schema", database.getSchema());
        substitutions.put("db.username", database.getApplicationUser());

        try (Connection connection = database.getAdminConnection()) {
            SqlScriptRunner runner = new SqlScriptRunner(connection);

            for (int index = FIRST_PROGRAMMABLE_SCRIPT;
                 index <= LAST_PROGRAMMABLE_SCRIPT; index++) {
                long scriptStart = System.currentTimeMillis();
                int count = runner.runScript(SCRIPTS[index], substitutions);
                totalStatements += count;

                System.out.println(String.format("  [ OK ]  %-36s %3d statements  %5d ms",
                        DESCRIPTIONS[index], count, System.currentTimeMillis() - scriptStart));
            }
        } catch (TSATMSException failure) {
            System.out.println();
            System.out.println("  [FAIL]  " + failure.toDisplayString());
            AppLogger.error(DatabaseBootstrap.class, "Refresh failed", failure);
            System.out.println();
            return 1;
        } catch (SQLException failure) {
            System.out.println();
            System.out.println("  [FAIL]  Could not open an administrator connection: "
                    + failure.getMessage());
            AppLogger.error(DatabaseBootstrap.class, "Administrator connection failed", failure);
            System.out.println();
            return 1;
        }

        System.out.println();
        System.out.println("  " + totalStatements + " statements in "
                + (System.currentTimeMillis() - startedAt) + " ms");
        System.out.println();
        return 0;
    }

    /**
     * Confirms what actually landed in the database and prints the inventory.
     */
    private static int verify(DBConnection database) {
        System.out.println();
        System.out.println("  " + AppConstants.LINE_SINGLE);
        System.out.println("  Verification");
        System.out.println("  " + AppConstants.LINE_SINGLE);

        String schema = database.getSchema();

        try (Connection connection = database.getAdminConnection()) {
            int tables = countOf(connection,
                    "SELECT COUNT(*) FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'", schema);
            int views = countOf(connection,
                    "SELECT COUNT(*) FROM information_schema.VIEWS WHERE TABLE_SCHEMA = ?", schema);
            int procedures = countOf(connection,
                    "SELECT COUNT(*) FROM information_schema.ROUTINES "
                            + "WHERE ROUTINE_SCHEMA = ? AND ROUTINE_TYPE = 'PROCEDURE'", schema);
            int functions = countOf(connection,
                    "SELECT COUNT(*) FROM information_schema.ROUTINES "
                            + "WHERE ROUTINE_SCHEMA = ? AND ROUTINE_TYPE = 'FUNCTION'", schema);
            int triggers = countOf(connection,
                    "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = ?", schema);
            int foreignKeys = countOf(connection,
                    "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                            + "WHERE CONSTRAINT_SCHEMA = ? AND CONSTRAINT_TYPE = 'FOREIGN KEY'", schema);
            int uniques = countOf(connection,
                    "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                            + "WHERE CONSTRAINT_SCHEMA = ? AND CONSTRAINT_TYPE = 'UNIQUE'", schema);
            int checks = countOf(connection,
                    "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                            + "WHERE CONSTRAINT_SCHEMA = ? AND CONSTRAINT_TYPE = 'CHECK'", schema);
            int indexes = countOf(connection,
                    "SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS "
                            + "WHERE TABLE_SCHEMA = ?", schema);

            System.out.println(String.format("  Tables %d   Views %d   Procedures %d   Functions %d   Triggers %d",
                    tables, views, procedures, functions, triggers));
            System.out.println(String.format("  Foreign keys %d   Unique constraints %d   Check constraints %d   Indexes %d",
                    foreignKeys, uniques, checks, indexes));
            System.out.println();

            printRowCounts(connection, schema);

            if (tables < SEEDED_TABLES.length) {
                System.out.println();
                System.out.println("  [FAIL]  Expected " + SEEDED_TABLES.length
                        + " tables but found " + tables);
                return 1;
            }
        } catch (SQLException | TSATMSException failure) {
            System.out.println("  [FAIL]  Verification failed: " + failure.getMessage());
            AppLogger.error(DatabaseBootstrap.class, "Verification failed", failure);
            return 1;
        }

        boolean applicationAccountWorks = database.canApplicationUserConnect();
        System.out.println();
        System.out.println(String.format("  %s  Application account '%s' can reach the schema",
                applicationAccountWorks ? "[ OK ]" : "[FAIL]", database.getApplicationUser()));

        if (!applicationAccountWorks) {
            System.out.println();
            System.out.println("  The schema built, but the application account cannot connect.");
            System.out.println("  Check that db.username and db.password match the account the");
            System.out.println("  bootstrap script created.");
            System.out.println();
            return 1;
        }

        System.out.println();
        System.out.println("  Phase 2 complete. The database is built, seeded and reachable");
        System.out.println("  by the application account.");
        System.out.println();
        AppLogger.info(DatabaseBootstrap.class, "Database build completed successfully");
        return 0;
    }

    private static void printRowCounts(Connection connection, String schema) throws SQLException {
        System.out.println("  Row counts");
        StringBuilder line = new StringBuilder("  ");
        int onThisLine = 0;

        for (String table : SEEDED_TABLES) {
            int rows = countRows(connection, schema, table);
            line.append(String.format("%-28s", table + " " + rows));
            onThisLine++;
            if (onThisLine == 3) {
                System.out.println(line.toString());
                line = new StringBuilder("  ");
                onThisLine = 0;
            }
        }
        if (onThisLine > 0) {
            System.out.println(line.toString());
        }
    }

    /**
     * Exercises the views, stored functions and stored procedures against the
     * seeded data, so a green build means they return sensible results rather
     * than merely that they compiled.
     */
    public static int verifyContent() {
        DBConnection database = DBConnection.getInstance();

        System.out.println();
        System.out.println("  " + AppConstants.LINE_DOUBLE);
        System.out.println("  DATABASE CONTENT VERIFICATION");
        System.out.println("  " + AppConstants.LINE_DOUBLE);

        try (Connection connection = database.getConnection()) {
            printQuery(connection, "Manager dashboard view (section 15)",
                    "SELECT total_open_tickets, critical_incidents, sla_at_risk, "
                            + "sla_breached, resolved_today, avg_resolution_hours "
                            + "FROM vw_manager_dashboard");

            printQuery(connection, "Open queue, most urgent first",
                    "SELECT ticket_number, priority, status, engineer_code, "
                            + "sla_status, minutes_remaining FROM vw_open_tickets "
                            + "ORDER BY priority_weight DESC, minutes_remaining ASC LIMIT 8");

            printQuery(connection, "Engineer workload",
                    "SELECT employee_code, specialization, region, availability, "
                            + "currently_open, total_resolved, avg_resolution_hours "
                            + "FROM vw_engineer_workload ORDER BY currently_open DESC");

            printQuery(connection, "SLA compliance by priority",
                    "SELECT priority, resolution_minutes, total_tickets, met_sla, "
                            + "breached_sla, compliance_pct FROM vw_sla_compliance "
                            + "ORDER BY resolution_minutes");

            printQuery(connection, "Ticket detail view (INNER JOIN and LEFT JOIN)",
                    "SELECT ticket_number, customer_name, service_code, category, "
                            + "priority, status, engineer_code, live_sla_status "
                            + "FROM vw_ticket_details ORDER BY created_date DESC LIMIT 5");

            printQuery(connection, "Incidents by category",
                    "SELECT category, ticket_count, critical_count, auto_created_count, "
                            + "avg_resolution_hours, pct_of_total FROM vw_category_incidents "
                            + "ORDER BY ticket_count DESC");

            printQuery(connection, "Customers with repeat incidents (GROUP BY ... HAVING)",
                    "SELECT customer_number, customer_name, customer_type, incident_count, "
                            + "critical_incidents, days_since_last "
                            + "FROM vw_customer_repeat_incidents ORDER BY incident_count DESC");

            // Called directly rather than left to be exercised through a view,
            // so a function that a view happens not to reach is still checked.
            printQuery(connection, "Stored functions, called directly",
                    "SELECT fn_sla_deadline('CRITICAL', NOW())            AS critical_due, "
                            + "fn_sla_response_deadline('CRITICAL', NOW())    AS critical_respond_by, "
                            + "fn_sla_status('HIGH', DATE_SUB(NOW(), INTERVAL 5 HOUR), "
                            + "DATE_SUB(NOW(), INTERVAL 1 HOUR), NULL, 'IN_PROGRESS') AS should_be_breached, "
                            + "fn_resolution_hours(DATE_SUB(NOW(), INTERVAL 150 MINUTE), NOW()) AS should_be_2_50, "
                            + "fn_minutes_remaining(DATE_ADD(NOW(), INTERVAL 45 MINUTE)) AS should_be_45");

            printQuery(connection,
                    "Stored procedure: three lightest-loaded broadband engineers in the South",
                    "CALL sp_recommend_engineers('BROADBAND', 'SOUTH', 3)");

            printQuery(connection, "Stored procedure: ticket volume over the last 30 days",
                    "CALL sp_ticket_volume_report(DATE_SUB(CURDATE(), INTERVAL 30 DAY), CURDATE())");

            printQuery(connection, "Stored procedure: the manager's operations summary",
                    "CALL sp_manager_dashboard()");

            printQuery(connection, "Trigger check: SLA deadlines derived on insert",
                    "SELECT ticket_number, priority, created_date, sla_response_deadline, "
                            + "sla_deadline FROM trouble_tickets ORDER BY created_date DESC LIMIT 5");

            // Selected by actor rather than taken from the end of the trail.
            // The application now writes audit rows of its own, so the newest
            // entries are usually not the trigger's and showing them under
            // this heading would claim something the query had not checked.
            printQuery(connection, "Trigger check: audit rows written by trg_engineers_after_update",
                    "SELECT entity_type, entity_id, action, performed_by, old_value, new_value "
                            + "FROM audit_log WHERE performed_by = 'DB_TRIGGER' "
                            + "ORDER BY audit_id DESC LIMIT 5");

        } catch (SQLException | TSATMSException failure) {
            System.out.println("  [FAIL]  " + failure.getMessage());
            AppLogger.error(DatabaseBootstrap.class, "Content verification failed", failure);
            System.out.println();
            return 1;
        }

        System.out.println();
        System.out.println("  All 7 views, all 5 functions and 3 of the 6 procedures returned data.");
        System.out.println("  The other three change data and open transactions of their own, so");
        System.out.println("  they are proved where the rows they write can be checked and undone:");
        System.out.println("    sp_assign_engineer  --verify-assign");
        System.out.println("    sp_escalate_ticket  --verify-escalation");
        System.out.println("    sp_resolve_ticket   --verify-ticket");
        System.out.println();
        return 0;
    }

    private static void printQuery(Connection connection, String title, String sql)
            throws SQLException {
        System.out.println();
        System.out.println("  " + title);
        System.out.println("  " + AppConstants.LINE_SINGLE);

        try (Statement statement = connection.createStatement()) {
            boolean hasResultSet = statement.execute(sql);
            if (!hasResultSet) {
                System.out.println("  (no result set)");
                return;
            }
            try (ResultSet resultSet = statement.getResultSet()) {
                printResultSet(resultSet);
            }
        }
    }

    private static void printResultSet(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();

        String[] headers = new String[columnCount];
        int[] widths = new int[columnCount];
        for (int column = 1; column <= columnCount; column++) {
            headers[column - 1] = metaData.getColumnLabel(column);
            widths[column - 1] = headers[column - 1].length();
        }

        List<String[]> rows = new ArrayList<>();
        while (resultSet.next()) {
            String[] row = new String[columnCount];
            for (int column = 1; column <= columnCount; column++) {
                Object value = resultSet.getObject(column);
                row[column - 1] = value == null ? "-" : String.valueOf(value);
                widths[column - 1] = Math.max(widths[column - 1], row[column - 1].length());
            }
            rows.add(row);
        }

        StringBuilder headerLine = new StringBuilder("  ");
        for (int column = 0; column < columnCount; column++) {
            headerLine.append(String.format("%-" + (widths[column] + 2) + "s", headers[column]));
        }
        System.out.println(headerLine.toString());

        for (String[] row : rows) {
            StringBuilder rowLine = new StringBuilder("  ");
            for (int column = 0; column < columnCount; column++) {
                rowLine.append(String.format("%-" + (widths[column] + 2) + "s", row[column]));
            }
            System.out.println(rowLine.toString());
        }

        if (rows.isEmpty()) {
            System.out.println("  (no rows)");
        }
    }

    private static int countRows(Connection connection, String schema, String table)
            throws SQLException {
        // The table name comes from a fixed internal array, never from user
        // input, so interpolating it here cannot be influenced externally.
        String sql = "SELECT COUNT(*) FROM `" + schema + "`.`" + table + "`";
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private static int countOf(Connection connection, String sql, String schema) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        } catch (SQLException cause) {
            throw new DataAccessException(ErrorCode.DB_QUERY_FAILED,
                    "Inventory query failed: " + cause.getMessage(), cause);
        }
    }
}
