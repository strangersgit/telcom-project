package com.amdocs.telecom.dao;

import com.amdocs.telecom.model.Customer;
import com.amdocs.telecom.model.enums.CustomerStatus;
import com.amdocs.telecom.model.enums.CustomerType;
import com.amdocs.telecom.model.enums.Region;

import java.util.List;
import java.util.Optional;

/**
 * Subscriber records.
 */
public interface CustomerDAO extends GenericDAO<Customer, Long> {

    Optional<Customer> findByCustomerNumber(String customerNumber);

    Optional<Customer> findByEmail(String email);

    /**
     * The customer behind a login account, used to scope the customer
     * dashboard to its owner.
     */
    Optional<Customer> findByUserId(Long userId);

    List<Customer> findByRegion(Region region);

    List<Customer> findByType(CustomerType customerType);

    List<Customer> findByStatus(CustomerStatus status);

    /**
     * Name or number match for the service desk's search box.
     */
    List<Customer> search(String fragment);

    boolean updateStatus(Long customerId, CustomerStatus status);

    /**
     * Highest customer number issued so far, so the next one continues the
     * sequence rather than colliding with it.
     */
    Optional<String> findHighestCustomerNumber();
}
