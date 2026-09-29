package com.amdocs.telecom.service.analytics;

import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.enums.CustomerType;
import com.amdocs.telecom.model.enums.Region;

import java.time.LocalDateTime;
import java.util.Comparator;

/**
 * A customer who has been back more than once.
 *
 * <p>The last of the nine analyses in section 16. The point of it is not
 * the count but what the count implies: a customer raising a fifth ticket
 * this month probably has one underlying fault that nobody has found, and
 * the tickets are five symptoms of it. So the latest incident is carried
 * alongside the total, because a customer with six tickets last year and a
 * customer with six this week need different conversations.</p>
 */
public final class RepeatCustomer implements Displayable {

    private final Long customerId;
    private final String customerNumber;
    private final String customerName;
    private final CustomerType customerType;
    private final String city;
    private final Region region;
    private final long incidentCount;
    private final long criticalIncidents;
    private final LocalDateTime latestIncident;

    RepeatCustomer(Long customerId, String customerNumber, String customerName,
                   CustomerType customerType, String city, Region region,
                   long incidentCount, long criticalIncidents, LocalDateTime latestIncident) {
        this.customerId = customerId;
        this.customerNumber = customerNumber;
        this.customerName = customerName;
        this.customerType = customerType;
        this.city = city;
        this.region = region;
        this.incidentCount = incidentCount;
        this.criticalIncidents = criticalIncidents;
        this.latestIncident = latestIncident;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getCustomerNumber() {
        return customerNumber;
    }

    public String getCustomerName() {
        return customerName;
    }

    public CustomerType getCustomerType() {
        return customerType;
    }

    public String getCity() {
        return city;
    }

    public Region getRegion() {
        return region;
    }

    public long getIncidentCount() {
        return incidentCount;
    }

    public long getCriticalIncidents() {
        return criticalIncidents;
    }

    public LocalDateTime getLatestIncident() {
        return latestIncident;
    }

    /**
     * Most incidents first, and among equals the one who was here most
     * recently, because that is the one still having the problem.
     */
    public static Comparator<RepeatCustomer> mostTroubledFirst() {
        return Comparator.comparingLong(RepeatCustomer::getIncidentCount).reversed()
                .thenComparing(RepeatCustomer::getLatestIncident,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(RepeatCustomer::getCustomerNumber);
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-12s %-24s %3d incident(s), %d critical, last %s",
                Displayable.orDash(customerNumber), Displayable.truncate(customerName, 24),
                incidentCount, criticalIncidents,
                Displayable.formatDateTime(latestIncident));
    }

    @Override
    public String toString() {
        return customerNumber + "=" + incidentCount;
    }
}
