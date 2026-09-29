package com.amdocs.telecom.dao.impl;

import com.amdocs.telecom.dao.RowMapper;
import com.amdocs.telecom.dao.SLAConfigurationDAO;
import com.amdocs.telecom.model.SLAConfiguration;
import com.amdocs.telecom.model.enums.Priority;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC access to the {@code sla_configuration} table.
 */
public class SLAConfigurationDAOImpl extends AbstractJdbcDAO<SLAConfiguration>
        implements SLAConfigurationDAO {

    private static final String INSERT_SQL =
            "INSERT INTO sla_configuration (priority, response_minutes, resolution_minutes, "
                    + "at_risk_threshold_pct, description, active) VALUES (?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE sla_configuration SET response_minutes = ?, resolution_minutes = ?, "
                    + "at_risk_threshold_pct = ?, description = ?, active = ? WHERE sla_config_id = ?";

    @Override
    public String tableName() {
        return "sla_configuration";
    }

    @Override
    protected String idColumn() {
        return "sla_config_id";
    }

    @Override
    protected RowMapper<SLAConfiguration> mapper() {
        return SLAConfigurationDAOImpl::mapRow;
    }

    static SLAConfiguration mapRow(ResultSet resultSet) throws SQLException {
        SLAConfiguration configuration = new SLAConfiguration();
        configuration.setId(resultSet.getLong("sla_config_id"));
        configuration.setPriority(JdbcSupport.enumValue(resultSet, "priority", Priority.class));
        configuration.setResponseMinutes(resultSet.getInt("response_minutes"));
        configuration.setResolutionMinutes(resultSet.getInt("resolution_minutes"));
        configuration.setAtRiskThresholdPercent(resultSet.getInt("at_risk_threshold_pct"));
        configuration.setDescription(resultSet.getString("description"));
        configuration.setActive(resultSet.getBoolean("active"));
        configuration.setCreatedAt(JdbcSupport.localDateTime(resultSet, "created_at"));
        configuration.setUpdatedAt(JdbcSupport.localDateTime(resultSet, "updated_at"));
        return configuration;
    }

    @Override
    protected String insertSql() {
        return INSERT_SQL;
    }

    @Override
    protected Object[] insertParameters(SLAConfiguration configuration) {
        return new Object[]{
                configuration.getPriority(),
                configuration.getResponseMinutes(),
                configuration.getResolutionMinutes(),
                configuration.getAtRiskThresholdPercent(),
                configuration.getDescription(),
                configuration.isActive()
        };
    }

    @Override
    protected String updateSql() {
        return UPDATE_SQL;
    }

    @Override
    protected Object[] updateParameters(SLAConfiguration configuration) {
        return new Object[]{
                configuration.getResponseMinutes(),
                configuration.getResolutionMinutes(),
                configuration.getAtRiskThresholdPercent(),
                configuration.getDescription(),
                configuration.isActive(),
                configuration.getId()
        };
    }

    /* ---------- Finders ---------- */

    @Override
    public Optional<SLAConfiguration> findByPriority(Priority priority) {
        return queryOne("SELECT * FROM sla_configuration WHERE priority = ?", priority);
    }

    @Override
    public List<SLAConfiguration> findAllActive() {
        return query("SELECT * FROM sla_configuration WHERE active = TRUE "
                + "ORDER BY resolution_minutes ASC");
    }

    /**
     * Loaded once and kept, so the SLA engine does not query per ticket. An
     * {@link EnumMap} rather than a hash map because the key is an enum and
     * the lookup is then an array index.
     */
    @Override
    public Map<Priority, SLAConfiguration> loadAsMap() {
        Map<Priority, SLAConfiguration> byPriority = new EnumMap<>(Priority.class);
        for (SLAConfiguration configuration : findAllActive()) {
            byPriority.put(configuration.getPriority(), configuration);
        }
        return byPriority;
    }

    @Override
    public boolean updateWindows(Priority priority, int responseMinutes, int resolutionMinutes) {
        return executeUpdate("UPDATE sla_configuration SET response_minutes = ?, "
                        + "resolution_minutes = ? WHERE priority = ?",
                responseMinutes, resolutionMinutes, priority) > 0;
    }
}
