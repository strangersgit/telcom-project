package com.amdocs.telecom.dao.impl;

import com.amdocs.telecom.dao.NetworkEngineerDAO;
import com.amdocs.telecom.dao.RowMapper;
import com.amdocs.telecom.model.NetworkEngineer;
import com.amdocs.telecom.model.enums.EngineerAvailability;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.model.enums.Specialization;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * JDBC access to the {@code network_engineers} table.
 */
public class NetworkEngineerDAOImpl extends AbstractJdbcDAO<NetworkEngineer>
        implements NetworkEngineerDAO {

    private static final String INSERT_SQL =
            "INSERT INTO network_engineers (employee_code, engineer_name, email, mobile_number, "
                    + "specialization, region, experience_years, availability, active_ticket_count, "
                    + "max_ticket_capacity, user_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE network_engineers SET engineer_name = ?, email = ?, mobile_number = ?, "
                    + "specialization = ?, region = ?, experience_years = ?, availability = ?, "
                    + "max_ticket_capacity = ?, user_id = ? WHERE engineer_id = ?";

    @Override
    public String tableName() {
        return "network_engineers";
    }

    @Override
    protected String idColumn() {
        return "engineer_id";
    }

    @Override
    protected String defaultOrderBy() {
        return "employee_code";
    }

    @Override
    protected RowMapper<NetworkEngineer> mapper() {
        return NetworkEngineerDAOImpl::mapRow;
    }

    static NetworkEngineer mapRow(ResultSet resultSet) throws SQLException {
        NetworkEngineer engineer = new NetworkEngineer();
        engineer.setId(resultSet.getLong("engineer_id"));
        engineer.setEmployeeCode(resultSet.getString("employee_code"));
        engineer.setName(resultSet.getString("engineer_name"));
        engineer.setEmail(resultSet.getString("email"));
        engineer.setMobileNumber(resultSet.getString("mobile_number"));
        engineer.setSpecialization(
                JdbcSupport.enumValue(resultSet, "specialization", Specialization.class));
        engineer.setRegion(JdbcSupport.enumValue(resultSet, "region", Region.class));
        engineer.setExperienceYears(resultSet.getInt("experience_years"));
        engineer.setAvailability(
                JdbcSupport.enumValue(resultSet, "availability", EngineerAvailability.class));
        engineer.setActiveTicketCount(resultSet.getInt("active_ticket_count"));
        engineer.setMaxTicketCapacity(resultSet.getInt("max_ticket_capacity"));
        engineer.setUserId(JdbcSupport.nullableLong(resultSet, "user_id"));
        engineer.setCreatedAt(JdbcSupport.localDateTime(resultSet, "created_at"));
        engineer.setUpdatedAt(JdbcSupport.localDateTime(resultSet, "updated_at"));
        return engineer;
    }

    @Override
    protected String insertSql() {
        return INSERT_SQL;
    }

    @Override
    protected Object[] insertParameters(NetworkEngineer engineer) {
        return new Object[]{
                engineer.getEmployeeCode(),
                engineer.getName(),
                engineer.getEmail(),
                engineer.getMobileNumber(),
                engineer.getSpecialization(),
                engineer.getRegion(),
                engineer.getExperienceYears(),
                engineer.getAvailability(),
                engineer.getActiveTicketCount(),
                engineer.getMaxTicketCapacity(),
                engineer.getUserId()
        };
    }

    @Override
    protected String updateSql() {
        return UPDATE_SQL;
    }

    /**
     * Note that {@code active_ticket_count} is not updated here. It is a
     * maintained aggregate owned by the assignment transaction, and letting
     * a general save overwrite it from a stale in-memory copy would undo
     * work another thread had just done.
     */
    @Override
    protected Object[] updateParameters(NetworkEngineer engineer) {
        return new Object[]{
                engineer.getName(),
                engineer.getEmail(),
                engineer.getMobileNumber(),
                engineer.getSpecialization(),
                engineer.getRegion(),
                engineer.getExperienceYears(),
                engineer.getAvailability(),
                engineer.getMaxTicketCapacity(),
                engineer.getUserId(),
                engineer.getId()
        };
    }

    /* ---------- Finders ---------- */

    @Override
    public Optional<NetworkEngineer> findByEmployeeCode(String employeeCode) {
        return queryOne("SELECT * FROM network_engineers WHERE employee_code = ?", employeeCode);
    }

    @Override
    public Optional<NetworkEngineer> findByUserId(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return queryOne("SELECT * FROM network_engineers WHERE user_id = ?", userId);
    }

    @Override
    public List<NetworkEngineer> findBySpecialization(Specialization specialization) {
        return query("SELECT * FROM network_engineers WHERE specialization = ? "
                + "ORDER BY employee_code", specialization);
    }

    @Override
    public List<NetworkEngineer> findByRegion(Region region) {
        return query("SELECT * FROM network_engineers WHERE region = ? ORDER BY employee_code",
                region);
    }

    /**
     * The region is optional so a caller can widen the search when nobody
     * local is free. The ordering matches
     * {@code NetworkEngineer.byWorkloadThenExperience()} and the
     * {@code sp_recommend_engineers} procedure, so all three agree.
     */
    @Override
    public List<NetworkEngineer> findAvailableFor(Specialization specialization, Region region) {
        return query("SELECT * FROM network_engineers "
                        + "WHERE specialization = ? "
                        + "  AND (? IS NULL OR region = ?) "
                        + "  AND availability = 'AVAILABLE' "
                        + "  AND active_ticket_count < max_ticket_capacity "
                        + "ORDER BY active_ticket_count ASC, experience_years DESC, employee_code ASC",
                specialization,
                region == null ? null : region.name(),
                region == null ? null : region.name());
    }

    /* ---------- Workload ---------- */

    @Override
    public boolean updateAvailability(Long engineerId, EngineerAvailability availability) {
        return executeUpdate("UPDATE network_engineers SET availability = ? WHERE engineer_id = ?",
                availability, engineerId) > 0;
    }

    /**
     * The capacity test lives in the WHERE clause rather than in Java, so
     * the check and the increment happen as one indivisible statement. Two
     * assignments racing for an engineer's last free slot cannot both
     * succeed.
     */
    @Override
    public boolean incrementWorkload(Long engineerId) {
        return executeUpdate("UPDATE network_engineers "
                + "SET active_ticket_count = active_ticket_count + 1 "
                + "WHERE engineer_id = ? AND active_ticket_count < max_ticket_capacity",
                engineerId) > 0;
    }

    @Override
    public boolean decrementWorkload(Long engineerId) {
        return executeUpdate("UPDATE network_engineers "
                + "SET active_ticket_count = active_ticket_count - 1 "
                + "WHERE engineer_id = ? AND active_ticket_count > 0",
                engineerId) > 0;
    }

    /**
     * The CASE derives the flag from the count in the same statement that
     * reads it, so there is no window in which the two disagree. The IN
     * clause is what keeps a manager's ON_LEAVE or OFF_SHIFT out of reach.
     *
     * <p>The same CASE appears again in the WHERE so that a row already
     * showing the right flag is never matched. Repeating it is the price of
     * an honest answer: the driver reports matched rows rather than changed
     * ones, so without this the method would claim a change every time it
     * was called.</p>
     */
    @Override
    public boolean refreshAvailability(Long engineerId) {
        String derived = "CASE WHEN active_ticket_count >= max_ticket_capacity "
                + "THEN 'BUSY' ELSE 'AVAILABLE' END";
        return executeUpdate("UPDATE network_engineers "
                + "SET availability = " + derived + " "
                + "WHERE engineer_id = ? "
                + "  AND availability IN ('AVAILABLE', 'BUSY') "
                + "  AND availability <> " + derived,
                engineerId) > 0;
    }

    @Override
    public int recalculateWorkloads() {
        return executeUpdate("UPDATE network_engineers e SET active_ticket_count = ("
                + "  SELECT COUNT(*) FROM trouble_tickets t "
                + "  WHERE t.assigned_engineer_id = e.engineer_id "
                + "    AND t.status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED'))");
    }
}
