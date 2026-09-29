package com.amdocs.telecom.dao.impl;

import com.amdocs.telecom.dao.LoginHistoryDAO;
import com.amdocs.telecom.dao.RowMapper;
import com.amdocs.telecom.model.LoginHistory;
import com.amdocs.telecom.model.enums.LoginStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * JDBC access to the {@code login_history} table.
 *
 * <p>Not quite append only: a session row is written when someone signs in
 * and completed when they sign out. Everything else about it is fixed.</p>
 */
public class LoginHistoryDAOImpl extends AbstractJdbcDAO<LoginHistory> implements LoginHistoryDAO {

    private static final String INSERT_SQL =
            "INSERT INTO login_history (user_id, username, login_time, logout_time, login_status, "
                    + "failure_reason, ip_address) VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE login_history SET logout_time = ? WHERE login_id = ?";

    @Override
    public String tableName() {
        return "login_history";
    }

    @Override
    protected String idColumn() {
        return "login_id";
    }

    @Override
    protected String defaultOrderBy() {
        return "login_time DESC";
    }

    @Override
    protected RowMapper<LoginHistory> mapper() {
        return LoginHistoryDAOImpl::mapRow;
    }

    static LoginHistory mapRow(ResultSet resultSet) throws SQLException {
        LoginHistory history = new LoginHistory();
        history.setId(resultSet.getLong("login_id"));
        history.setUserId(JdbcSupport.nullableLong(resultSet, "user_id"));
        history.setUsername(resultSet.getString("username"));
        history.setLoginTime(JdbcSupport.localDateTime(resultSet, "login_time"));
        history.setLogoutTime(JdbcSupport.localDateTime(resultSet, "logout_time"));
        history.setLoginStatus(JdbcSupport.enumValue(resultSet, "login_status", LoginStatus.class));
        history.setFailureReason(resultSet.getString("failure_reason"));
        history.setIpAddress(resultSet.getString("ip_address"));
        return history;
    }

    @Override
    protected String insertSql() {
        return INSERT_SQL;
    }

    @Override
    protected Object[] insertParameters(LoginHistory history) {
        return new Object[]{
                history.getUserId(),
                history.getUsername(),
                history.getLoginTime() == null ? LocalDateTime.now() : history.getLoginTime(),
                history.getLogoutTime(),
                history.getLoginStatus(),
                history.getFailureReason(),
                history.getIpAddress()
        };
    }

    /**
     * Only the sign out time can change. The attempt itself, and whether it
     * succeeded, are a matter of record.
     */
    @Override
    protected String updateSql() {
        return UPDATE_SQL;
    }

    @Override
    protected Object[] updateParameters(LoginHistory history) {
        return new Object[]{history.getLogoutTime(), history.getId()};
    }

    /**
     * Newest first, with the key breaking ties.
     *
     * <p>{@code login_time} is a DATETIME and so is only accurate to the
     * second, while a run of failed attempts can easily land several rows
     * inside one. Ordering on the time alone leaves those rows tied and the
     * database free to return them in any order, which would make the most
     * recent attempt the wrong one. The auto-increment key always increases
     * with insertion, so it settles the tie correctly.</p>
     */
    @Override
    public List<LoginHistory> findByUserId(Long userId, int limit) {
        return query("SELECT * FROM login_history WHERE user_id = ? "
                + "ORDER BY login_time DESC, login_id DESC LIMIT ?", userId, limit);
    }

    @Override
    public List<LoginHistory> findByUsername(String username, int limit) {
        return query("SELECT * FROM login_history WHERE username = ? "
                + "ORDER BY login_time DESC, login_id DESC LIMIT ?", username, limit);
    }

    @Override
    public List<LoginHistory> findByStatus(LoginStatus status, int limit) {
        return query("SELECT * FROM login_history WHERE login_status = ? "
                + "ORDER BY login_time DESC, login_id DESC LIMIT ?", status, limit);
    }

    /**
     * Counts only the outcomes that represent a wrong credential. A failed
     * CAPTCHA is recorded but does not count towards the lockout, otherwise
     * a mistyped code would lock a legitimate account.
     */
    @Override
    public long countFailuresSince(String username, LocalDateTime since) {
        return queryLong("SELECT COUNT(*) FROM login_history "
                        + "WHERE username = ? AND login_time >= ? "
                        + "AND login_status IN ('FAILED', 'OTP_FAILED')",
                username, since).orElse(0L);
    }

    /**
     * Only completes a session that is genuinely open, so a second sign out
     * cannot move the time.
     */
    @Override
    public boolean recordLogout(Long loginId, LocalDateTime logoutTime) {
        return executeUpdate("UPDATE login_history SET logout_time = ? "
                        + "WHERE login_id = ? AND logout_time IS NULL",
                logoutTime, loginId) > 0;
    }

    @Override
    public List<LoginHistory> findRecent(int limit) {
        return query("SELECT * FROM login_history "
                + "ORDER BY login_time DESC, login_id DESC LIMIT ?", limit);
    }
}
