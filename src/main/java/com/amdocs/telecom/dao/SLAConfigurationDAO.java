package com.amdocs.telecom.dao;

import com.amdocs.telecom.model.SLAConfiguration;
import com.amdocs.telecom.model.enums.Priority;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The response and resolution windows from section 8.
 */
public interface SLAConfigurationDAO extends GenericDAO<SLAConfiguration, Long> {

    Optional<SLAConfiguration> findByPriority(Priority priority);

    List<SLAConfiguration> findAllActive();

    /**
     * The whole table keyed by priority, so the SLA engine can look windows
     * up without a query per ticket.
     */
    Map<Priority, SLAConfiguration> loadAsMap();

    boolean updateWindows(Priority priority, int responseMinutes, int resolutionMinutes);
}
