package com.amdocs.telecom.util;

import com.amdocs.telecom.exception.DataAccessException;
import com.amdocs.telecom.exception.ErrorCode;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Central supplier of JDBC connections, implemented as a singleton using the
 * initialisation-on-demand holder idiom.
 *
 * <p>Two distinct identities are served. The application account is used for
 * all normal work and is confined to the application schema. The
 * administrator account is used only by the schema bootstrap and by
 * diagnostics, and connects at server level so it can report on a schema that
 * does not exist yet.</p>
 */
public final class DBConnection {

    private final String driverClass;
    private final String host;
    private final int port;
    private final String schema;
    private final String parameters;

    private final String applicationUser;
    private final String applicationPassword;
    private final String administratorUser;
    private final String administratorPassword;

    private final int validationTimeoutSeconds;
    private final boolean defaultAutoCommit;

    private DBConnection() {
        ConfigLoader config = ConfigLoader.getInstance();

        this.driverClass = config.getString("db.driver", "com.mysql.cj.jdbc.Driver");
        this.host = config.getString("db.host", "localhost");
        this.port = config.getInt("db.port", 3306);
        this.schema = config.getRequired("db.schema");
        this.parameters = config.getString("db.params", "useSSL=false&allowPublicKeyRetrieval=true");

        this.applicationUser = config.getRequired("db.username");
        this.applicationPassword = config.getString("db.password", "");
        this.administratorUser = config.getString("db.admin.username", "root");
        this.administratorPassword = config.getString("db.admin.password", "");

        this.validationTimeoutSeconds = config.getInt("db.validation.timeout.seconds", 5);
        this.defaultAutoCommit = config.getBoolean("db.autocommit", true);

        DriverManager.setLoginTimeout(config.getInt("db.connection.timeout.seconds", 10));
        loadDriver();
    }

    private static final class Holder {
        private static final DBConnection INSTANCE = new DBConnection();
    }

    public static DBConnection getInstance() {
        return Holder.INSTANCE;
    }

    private void loadDriver() {
        try {
            Class.forName(driverClass);
            AppLogger.debug(DBConnection.class, "Loaded JDBC driver " + driverClass);
        } catch (ClassNotFoundException cause) {
            throw new DataAccessException(ErrorCode.DB_DRIVER_NOT_FOUND,
                    "JDBC driver '" + driverClass + "' is not on the classpath", cause);
        }
    }

    /* ---------- URL construction ---------- */

    /**
     * Server level URL with no schema selected.
     */
    public String getServerUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/?" + parameters;
    }

    /**
     * Application URL pointing at the project schema.
     */
    public String getApplicationUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + schema + "?" + parameters;
    }

    /* ---------- Connection factories ---------- */

    /**
     * Opens a connection as the application account against the project
     * schema. Callers are responsible for closing it, normally with
     * try-with-resources.
     *
     * @throws DataAccessException when the connection cannot be established
     */
    public Connection getConnection() {
        try {
            Connection connection = DriverManager.getConnection(
                    getApplicationUrl(), applicationUser, applicationPassword);
            connection.setAutoCommit(defaultAutoCommit);
            return connection;
        } catch (SQLException cause) {
            throw new DataAccessException(ErrorCode.DB_CONNECTION_FAILED,
                    "Could not connect to " + schema + " as '" + applicationUser + "': " + cause.getMessage(),
                    cause);
        }
    }

    /**
     * Opens a server level connection as the administrator account. Used by
     * the schema bootstrap, which must run before the schema exists.
     */
    public Connection getAdminConnection() {
        try {
            return DriverManager.getConnection(
                    getServerUrl(), administratorUser, administratorPassword);
        } catch (SQLException cause) {
            throw new DataAccessException(ErrorCode.DB_CONNECTION_FAILED,
                    "Could not connect to the MySQL server as '" + administratorUser + "': "
                            + cause.getMessage(), cause);
        }
    }

    /* ---------- Diagnostics ---------- */

    /**
     * Whether the MySQL server accepts the administrator credentials.
     */
    public boolean isServerReachable() {
        try (Connection connection = getAdminConnection()) {
            return connection.isValid(validationTimeoutSeconds);
        } catch (SQLException | DataAccessException cause) {
            AppLogger.debug(DBConnection.class, "Server reachability check failed: " + cause.getMessage());
            return false;
        }
    }

    /**
     * Product name and version of the connected server, when reachable.
     */
    public Optional<String> getServerVersion() {
        try (Connection connection = getAdminConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            return Optional.of(metaData.getDatabaseProductName() + " " + metaData.getDatabaseProductVersion());
        } catch (SQLException | DataAccessException cause) {
            return Optional.empty();
        }
    }

    /**
     * Whether the application schema has been created yet.
     */
    public boolean schemaExists() {
        final String sql = "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?";
        try (Connection connection = getAdminConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException | DataAccessException cause) {
            AppLogger.debug(DBConnection.class, "Schema check failed: " + cause.getMessage());
            return false;
        }
    }

    /**
     * Whether the dedicated application account can reach the schema. False
     * until the bootstrap has created both.
     */
    public boolean canApplicationUserConnect() {
        try (Connection connection = getConnection()) {
            return connection.isValid(validationTimeoutSeconds);
        } catch (SQLException | DataAccessException cause) {
            AppLogger.debug(DBConnection.class, "Application account check failed: " + cause.getMessage());
            return false;
        }
    }

    /* ---------- Accessors ---------- */

    public String getSchema() {
        return schema;
    }

    public String getApplicationUser() {
        return applicationUser;
    }

    public String getAdministratorUser() {
        return administratorUser;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    /* ---------- Resource helpers ---------- */

    /**
     * Closes any number of JDBC resources without throwing, for use in
     * {@code finally} blocks where a failure to close must not mask the
     * original problem.
     */
    public static void closeQuietly(AutoCloseable... resources) {
        if (resources == null) {
            return;
        }
        for (AutoCloseable resource : resources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Exception cause) {
                AppLogger.debug(DBConnection.class, "Suppressed error while closing: " + cause.getMessage());
            }
        }
    }

    /**
     * Rolls a transaction back without throwing, so the original failure is
     * the one that reaches the caller.
     */
    public static void rollbackQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            if (!connection.getAutoCommit()) {
                connection.rollback();
                AppLogger.warn(DBConnection.class, "Transaction rolled back");
            }
        } catch (SQLException cause) {
            AppLogger.error(DBConnection.class, "Rollback failed", cause);
        }
    }

    /**
     * Restores autocommit before a connection returns to normal use.
     */
    public static void restoreAutoCommit(Connection connection, boolean autoCommit) {
        if (connection == null) {
            return;
        }
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException cause) {
            AppLogger.debug(DBConnection.class, "Could not restore autocommit: " + cause.getMessage());
        }
    }
}
