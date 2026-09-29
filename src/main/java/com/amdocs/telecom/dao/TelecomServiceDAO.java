package com.amdocs.telecom.dao;

import com.amdocs.telecom.model.TelecomService;
import com.amdocs.telecom.model.enums.ServiceStatus;
import com.amdocs.telecom.model.enums.ServiceType;

import java.util.List;
import java.util.Optional;

/**
 * Subscribed services, which are what tickets are raised against.
 */
public interface TelecomServiceDAO extends GenericDAO<TelecomService, Long> {

    Optional<TelecomService> findByServiceCode(String serviceCode);

    List<TelecomService> findByCustomerId(Long customerId);

    /**
     * Only the services a customer may actually raise a ticket against, so
     * the console never offers a terminated line.
     */
    List<TelecomService> findTicketableByCustomerId(Long customerId);

    List<TelecomService> findByType(ServiceType serviceType);

    List<TelecomService> findByStatus(ServiceStatus serviceStatus);

    boolean updateStatus(Long serviceId, ServiceStatus serviceStatus);
}
