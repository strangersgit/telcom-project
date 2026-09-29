package com.amdocs.telecom.dao.impl;

import com.amdocs.telecom.dao.CustomerDAO;
import com.amdocs.telecom.dao.RowMapper;
import com.amdocs.telecom.model.Customer;
import com.amdocs.telecom.model.enums.CustomerStatus;
import com.amdocs.telecom.model.enums.CustomerType;
import com.amdocs.telecom.model.enums.Region;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * JDBC access to the {@code customers} table.
 */
public class CustomerDAOImpl extends AbstractJdbcDAO<Customer> implements CustomerDAO {

    private static final String INSERT_SQL =
            "INSERT INTO customers (customer_number, customer_name, email, mobile_number, "
                    + "customer_type, city, region, status, user_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE customers SET customer_name = ?, email = ?, mobile_number = ?, "
                    + "customer_type = ?, city = ?, region = ?, status = ?, user_id = ? "
                    + "WHERE customer_id = ?";

    @Override
    public String tableName() {
        return "customers";
    }

    @Override
    protected String idColumn() {
        return "customer_id";
    }

    @Override
    protected String defaultOrderBy() {
        return "customer_number";
    }

    @Override
    protected RowMapper<Customer> mapper() {
        return CustomerDAOImpl::mapRow;
    }

    static Customer mapRow(ResultSet resultSet) throws SQLException {
        Customer customer = new Customer();
        customer.setId(resultSet.getLong("customer_id"));
        customer.setCustomerNumber(resultSet.getString("customer_number"));
        customer.setName(resultSet.getString("customer_name"));
        customer.setEmail(resultSet.getString("email"));
        customer.setMobileNumber(resultSet.getString("mobile_number"));
        customer.setCustomerType(JdbcSupport.enumValue(resultSet, "customer_type", CustomerType.class));
        customer.setCity(resultSet.getString("city"));
        customer.setRegion(JdbcSupport.enumValue(resultSet, "region", Region.class));
        customer.setStatus(JdbcSupport.enumValue(resultSet, "status", CustomerStatus.class));
        customer.setUserId(JdbcSupport.nullableLong(resultSet, "user_id"));
        customer.setCreatedAt(JdbcSupport.localDateTime(resultSet, "created_at"));
        customer.setUpdatedAt(JdbcSupport.localDateTime(resultSet, "updated_at"));
        return customer;
    }

    @Override
    protected String insertSql() {
        return INSERT_SQL;
    }

    @Override
    protected Object[] insertParameters(Customer customer) {
        return new Object[]{
                customer.getCustomerNumber(),
                customer.getName(),
                customer.getEmail(),
                customer.getMobileNumber(),
                customer.getCustomerType(),
                customer.getCity(),
                customer.getRegion(),
                customer.getStatus(),
                customer.getUserId()
        };
    }

    @Override
    protected String updateSql() {
        return UPDATE_SQL;
    }

    @Override
    protected Object[] updateParameters(Customer customer) {
        return new Object[]{
                customer.getName(),
                customer.getEmail(),
                customer.getMobileNumber(),
                customer.getCustomerType(),
                customer.getCity(),
                customer.getRegion(),
                customer.getStatus(),
                customer.getUserId(),
                customer.getId()
        };
    }

    /* ---------- Finders ---------- */

    @Override
    public Optional<Customer> findByCustomerNumber(String customerNumber) {
        return queryOne("SELECT * FROM customers WHERE customer_number = ?", customerNumber);
    }

    @Override
    public Optional<Customer> findByEmail(String email) {
        return queryOne("SELECT * FROM customers WHERE email = ?", email);
    }

    @Override
    public Optional<Customer> findByUserId(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return queryOne("SELECT * FROM customers WHERE user_id = ?", userId);
    }

    @Override
    public List<Customer> findByRegion(Region region) {
        return query("SELECT * FROM customers WHERE region = ? ORDER BY customer_name", region);
    }

    @Override
    public List<Customer> findByType(CustomerType customerType) {
        return query("SELECT * FROM customers WHERE customer_type = ? ORDER BY customer_name",
                customerType);
    }

    @Override
    public List<Customer> findByStatus(CustomerStatus status) {
        return query("SELECT * FROM customers WHERE status = ? ORDER BY customer_name", status);
    }

    @Override
    public List<Customer> search(String fragment) {
        String pattern = "%" + (fragment == null ? "" : fragment.trim()) + "%";
        return query("SELECT * FROM customers WHERE customer_number LIKE ? OR customer_name LIKE ? "
                + "OR email LIKE ? ORDER BY customer_name", pattern, pattern, pattern);
    }

    @Override
    public boolean updateStatus(Long customerId, CustomerStatus status) {
        return executeUpdate("UPDATE customers SET status = ? WHERE customer_id = ?",
                status, customerId) > 0;
    }

    @Override
    public Optional<String> findHighestCustomerNumber() {
        return queryOne("SELECT MAX(customer_number) FROM customers",
                resultSet -> resultSet.getString(1));
    }
}
