package com.amdocs.telecom.dto;

import com.amdocs.telecom.model.Displayable;
import com.amdocs.telecom.model.enums.CustomerType;
import com.amdocs.telecom.model.enums.Region;

import java.time.LocalDateTime;

/**
 * A customer who has raised more than one incident, matching
 * {@code vw_customer_repeat_incidents}.
 *
 * <p>Repeat reporters are the ones worth investigating: several tickets from
 * one customer usually points at a single underlying fault rather than
 * several unrelated ones.</p>
 */
public class RepeatIncidentDTO implements Displayable {

    private Long customerId;
    private String customerNumber;
    private String customerName;
    private CustomerType customerType;
    private String city;
    private Region region;
    private int incidentCount;
    private int criticalIncidents;
    private LocalDateTime latestIncident;
    private Integer daysSinceLast;

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public String getCustomerNumber() {
        return customerNumber;
    }

    public void setCustomerNumber(String customerNumber) {
        this.customerNumber = customerNumber;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public CustomerType getCustomerType() {
        return customerType;
    }

    public void setCustomerType(CustomerType customerType) {
        this.customerType = customerType;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public Region getRegion() {
        return region;
    }

    public void setRegion(Region region) {
        this.region = region;
    }

    public int getIncidentCount() {
        return incidentCount;
    }

    public void setIncidentCount(int incidentCount) {
        this.incidentCount = incidentCount;
    }

    public int getCriticalIncidents() {
        return criticalIncidents;
    }

    public void setCriticalIncidents(int criticalIncidents) {
        this.criticalIncidents = criticalIncidents;
    }

    public LocalDateTime getLatestIncident() {
        return latestIncident;
    }

    public void setLatestIncident(LocalDateTime latestIncident) {
        this.latestIncident = latestIncident;
    }

    public Integer getDaysSinceLast() {
        return daysSinceLast;
    }

    public void setDaysSinceLast(Integer daysSinceLast) {
        this.daysSinceLast = daysSinceLast;
    }

    /**
     * Repeat enterprise customers with critical incidents are the ones the
     * manager dashboard flags first.
     */
    public boolean needsAttention() {
        return incidentCount >= 3 || criticalIncidents >= 2;
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-12s %-26s %-12s %-14s %6d %9d %-18s %s",
                Displayable.orDash(customerNumber),
                Displayable.truncate(customerName, 26),
                customerType == null ? "-" : customerType.getDisplayName(),
                Displayable.orDash(city),
                incidentCount,
                criticalIncidents,
                Displayable.formatDateTime(latestIncident),
                daysSinceLast == null ? "-" : daysSinceLast + "d ago");
    }
}
