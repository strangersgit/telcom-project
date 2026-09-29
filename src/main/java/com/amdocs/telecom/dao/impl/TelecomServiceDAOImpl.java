package com.amdocs.telecom.dao.impl;

import com.amdocs.telecom.dao.RowMapper;
import com.amdocs.telecom.dao.TelecomServiceDAO;
import com.amdocs.telecom.model.TelecomService;
import com.amdocs.telecom.model.enums.ServiceStatus;
import com.amdocs.telecom.model.enums.ServiceType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * JDBC access to the {@code telecom_services} table.
 */
public class TelecomServiceDAOImpl extends AbstractJdbcDAO<TelecomService>
        implements TelecomServiceDAO {

    private static final String INSERT_SQL =
            "INSERT INTO telecom_services (service_code, service_name, service_type, customer_id, "
                    + "activation_date, service_status) VALUES (?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE telecom_services SET service_name = ?, service_type = ?, customer_id = ?, "
                    + "activation_date = ?, service_status = ? WHERE service_id = ?";

    @Override
    public String tableName() {
        return "telecom_services";
    }

    @Override
    protected String idColumn() {
        return "service_id";
    }

    @Override
    protected String defaultOrderBy() {
        return "service_code";
    }

    @Override
    protected RowMapper<TelecomService> mapper() {
        return TelecomServiceDAOImpl::mapRow;
    }

    static TelecomService mapRow(ResultSet resultSet) throws SQLException {
        TelecomService service = new TelecomService();
        service.setId(resultSet.getLong("service_id"));
        service.setServiceCode(resultSet.getString("service_code"));
        service.setServiceName(resultSet.getString("service_name"));
        service.setServiceType(JdbcSupport.enumValue(resultSet, "service_type", ServiceType.class));
        service.setCustomerId(resultSet.getLong("customer_id"));
        service.setActivationDate(JdbcSupport.localDate(resultSet, "activation_date"));
        service.setServiceStatus(JdbcSupport.enumValue(resultSet, "service_status", ServiceStatus.class));
        service.setCreatedAt(JdbcSupport.localDateTime(resultSet, "created_at"));
        service.setUpdatedAt(JdbcSupport.localDateTime(resultSet, "updated_at"));
        return service;
    }

    @Override
    protected String insertSql() {
        return INSERT_SQL;
    }

    @Override
    protected Object[] insertParameters(TelecomService service) {
        return new Object[]{
                service.getServiceCode(),
                service.getServiceName(),
                service.getServiceType(),
                service.getCustomerId(),
                service.getActivationDate(),
                service.getServiceStatus()
        };
    }

    @Override
    protected String updateSql() {
        return UPDATE_SQL;
    }

    @Override
    protected Object[] updateParameters(TelecomService service) {
        return new Object[]{
                service.getServiceName(),
                service.getServiceType(),
                service.getCustomerId(),
                service.getActivationDate(),
                service.getServiceStatus(),
                service.getId()
        };
    }

    /* ---------- Finders ---------- */

    @Override
    public Optional<TelecomService> findByServiceCode(String serviceCode) {
        return queryOne("SELECT * FROM telecom_services WHERE service_code = ?", serviceCode);
    }

    @Override
    public List<TelecomService> findByCustomerId(Long customerId) {
        return query("SELECT * FROM telecom_services WHERE customer_id = ? ORDER BY service_code",
                customerId);
    }

    @Override
    public List<TelecomService> findTicketableByCustomerId(Long customerId) {
        return query("SELECT * FROM telecom_services WHERE customer_id = ? "
                        + "AND service_status IN ('ACTIVE', 'SUSPENDED') ORDER BY service_code",
                customerId);
    }

    @Override
    public List<TelecomService> findByType(ServiceType serviceType) {
        return query("SELECT * FROM telecom_services WHERE service_type = ? ORDER BY service_code",
                serviceType);
    }

    @Override
    public List<TelecomService> findByStatus(ServiceStatus serviceStatus) {
        return query("SELECT * FROM telecom_services WHERE service_status = ? ORDER BY service_code",
                serviceStatus);
    }

    @Override
    public boolean updateStatus(Long serviceId, ServiceStatus serviceStatus) {
        return executeUpdate("UPDATE telecom_services SET service_status = ? WHERE service_id = ?",
                serviceStatus, serviceId) > 0;
    }
}
