package com.amdocs.telecom.dao.impl;

import com.amdocs.telecom.dao.RowMapper;
import com.amdocs.telecom.dao.UserDAO;
import com.amdocs.telecom.model.UserAccount;
import com.amdocs.telecom.model.enums.AccountStatus;
import com.amdocs.telecom.model.enums.Role;
import com.amdocs.telecom.util.AppConstants;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * JDBC access to the {@code users} table.
 */
public class UserDAOImpl extends AbstractJdbcDAO<UserAccount> implements UserDAO {

    private static final String INSERT_SQL =
            "INSERT INTO users (username, password_hash, password_salt, full_name, email, role, "
                    + "account_status, failed_login_attempts, locked_until, last_login_date, "
                    + "must_change_password) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE users SET full_name = ?, email = ?, role = ?, account_status = ?, "
                    + "failed_login_attempts = ?, locked_until = ?, last_login_date = ?, "
                    + "must_change_password = ? WHERE user_id = ?";

    @Override
    public String tableName() {
        return "users";
    }

    @Override
    protected String idColumn() {
        return "user_id";
    }

    @Override
    protected String defaultOrderBy() {
        return "username";
    }

    @Override
    protected RowMapper<UserAccount> mapper() {
        return UserDAOImpl::mapRow;
    }

    static UserAccount mapRow(ResultSet resultSet) throws SQLException {
        UserAccount user = new UserAccount();
        user.setId(resultSet.getLong("user_id"));
        user.setUsername(resultSet.getString("username"));
        user.setPasswordHash(resultSet.getString("password_hash"));
        user.setPasswordSalt(resultSet.getString("password_salt"));
        user.setName(resultSet.getString("full_name"));
        user.setEmail(resultSet.getString("email"));
        user.setRole(JdbcSupport.enumValue(resultSet, "role", Role.class));
        user.setAccountStatus(JdbcSupport.enumValue(resultSet, "account_status", AccountStatus.class));
        user.setFailedLoginAttempts(resultSet.getInt("failed_login_attempts"));
        user.setLockedUntil(JdbcSupport.localDateTime(resultSet, "locked_until"));
        user.setLastLoginDate(JdbcSupport.localDateTime(resultSet, "last_login_date"));
        user.setMustChangePassword(resultSet.getBoolean("must_change_password"));
        user.setCreatedAt(JdbcSupport.localDateTime(resultSet, "created_at"));
        user.setUpdatedAt(JdbcSupport.localDateTime(resultSet, "updated_at"));
        return user;
    }

    @Override
    protected String insertSql() {
        return INSERT_SQL;
    }

    @Override
    protected Object[] insertParameters(UserAccount user) {
        return new Object[]{
                user.getUsername(),
                user.getPasswordHash(),
                user.getPasswordSalt(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getAccountStatus(),
                user.getFailedLoginAttempts(),
                user.getLockedUntil(),
                user.getLastLoginDate(),
                user.isMustChangePassword()
        };
    }

    @Override
    protected String updateSql() {
        return UPDATE_SQL;
    }

    @Override
    protected Object[] updateParameters(UserAccount user) {
        return new Object[]{
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getAccountStatus(),
                user.getFailedLoginAttempts(),
                user.getLockedUntil(),
                user.getLastLoginDate(),
                user.isMustChangePassword(),
                user.getId()
        };
    }

    /* ---------- Finders ---------- */

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        return queryOne("SELECT * FROM users WHERE username = ?", username);
    }

    @Override
    public Optional<UserAccount> findByEmail(String email) {
        return queryOne("SELECT * FROM users WHERE email = ?", email);
    }

    @Override
    public List<UserAccount> findByRole(Role role) {
        return query("SELECT * FROM users WHERE role = ? ORDER BY username", role);
    }

    @Override
    public List<UserAccount> findByStatus(AccountStatus status) {
        return query("SELECT * FROM users WHERE account_status = ? ORDER BY username", status);
    }

    @Override
    public List<UserAccount> findAwaitingPassword() {
        return query("SELECT * FROM users WHERE password_hash = ? ORDER BY username",
                AppConstants.PENDING_PASSWORD_SENTINEL);
    }

    /* ---------- Authentication updates ---------- */

    /**
     * The counter is assigned last on purpose. MySQL evaluates the SET
     * clauses left to right and a later clause sees the values written by an
     * earlier one, so incrementing first would make both CASE expressions
     * compare against the already incremented count and lock an attempt
     * early.
     */
    @Override
    public Optional<UserAccount> registerFailedAttempt(Long userId, int lockThreshold,
                                                       int lockMinutes) {
        executeUpdate("UPDATE users SET "
                        + "  account_status = CASE WHEN failed_login_attempts + 1 >= ? "
                        + "                        THEN ? ELSE account_status END, "
                        + "  locked_until = CASE WHEN failed_login_attempts + 1 >= ? "
                        + "                      THEN DATE_ADD(NOW(), INTERVAL ? MINUTE) "
                        + "                      ELSE locked_until END, "
                        + "  failed_login_attempts = failed_login_attempts + 1 "
                        + "WHERE user_id = ?",
                lockThreshold, AccountStatus.LOCKED, lockThreshold, lockMinutes, userId);

        return findById(userId);
    }

    @Override
    public boolean recordSuccessfulLogin(Long userId, LocalDateTime loginTime) {
        return executeUpdate(
                "UPDATE users SET failed_login_attempts = 0, locked_until = NULL, "
                        + "account_status = ?, last_login_date = ? WHERE user_id = ?",
                AccountStatus.ACTIVE, loginTime, userId) > 0;
    }

    @Override
    public boolean updatePassword(Long userId, String passwordHash, String passwordSalt,
                                  boolean mustChangePassword) {
        return executeUpdate(
                "UPDATE users SET password_hash = ?, password_salt = ?, must_change_password = ?, "
                        + "failed_login_attempts = 0, locked_until = NULL WHERE user_id = ?",
                passwordHash, passwordSalt, mustChangePassword, userId) > 0;
    }

    @Override
    public boolean updateStatus(Long userId, AccountStatus status, LocalDateTime lockedUntil) {
        return executeUpdate(
                "UPDATE users SET account_status = ?, locked_until = ? WHERE user_id = ?",
                status, lockedUntil, userId) > 0;
    }

    @Override
    public boolean unlock(Long userId) {
        return executeUpdate("UPDATE users SET account_status = ?, locked_until = NULL, "
                        + "failed_login_attempts = 0 WHERE user_id = ?",
                AccountStatus.ACTIVE, userId) > 0;
    }
}
