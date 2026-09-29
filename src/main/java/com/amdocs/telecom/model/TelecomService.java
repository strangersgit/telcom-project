package com.amdocs.telecom.model;

import com.amdocs.telecom.model.enums.ServiceStatus;
import com.amdocs.telecom.model.enums.ServiceType;

import java.time.LocalDate;

/**
 * A service a customer subscribes to, and the thing a trouble ticket is
 * ultimately raised against.
 */
public class TelecomService extends TimestampedEntity implements Auditable {

    private static final long serialVersionUID = 1L;

    private String serviceCode;
    private String serviceName;
    private ServiceType serviceType;
    private Long customerId;
    private LocalDate activationDate;
    private ServiceStatus serviceStatus = ServiceStatus.ACTIVE;

    public TelecomService() {
        super();
    }

    public TelecomService(String serviceCode, String serviceName, ServiceType serviceType,
                          Long customerId, LocalDate activationDate) {
        this.serviceCode = serviceCode;
        this.serviceName = serviceName;
        this.serviceType = serviceType;
        this.customerId = customerId;
        this.activationDate = activationDate;
    }

    public String getServiceCode() {
        return serviceCode;
    }

    public void setServiceCode(String serviceCode) {
        this.serviceCode = serviceCode;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public ServiceType getServiceType() {
        return serviceType;
    }

    public void setServiceType(ServiceType serviceType) {
        this.serviceType = serviceType;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public LocalDate getActivationDate() {
        return activationDate;
    }

    public void setActivationDate(LocalDate activationDate) {
        this.activationDate = activationDate;
    }

    public ServiceStatus getServiceStatus() {
        return serviceStatus;
    }

    public void setServiceStatus(ServiceStatus serviceStatus) {
        this.serviceStatus = serviceStatus;
    }

    /**
     * A ticket can only be raised against a service that is live or degraded,
     * never one that was never activated or has been terminated.
     */
    public boolean isTicketable() {
        return serviceStatus != null && serviceStatus.isTicketable();
    }

    public boolean isBusinessGrade() {
        return serviceType != null && serviceType.isBusinessGrade();
    }

    @Override
    public String getAuditEntityType() {
        return "TELECOM_SERVICE";
    }

    @Override
    public String getAuditEntityId() {
        return serviceCode;
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-12s %-30s %-24s %-12s",
                Displayable.orDash(serviceCode),
                Displayable.truncate(serviceName, 30),
                serviceType == null ? "-" : serviceType.getDisplayName(),
                serviceStatus == null ? "-" : serviceStatus.getDisplayName());
    }

    @Override
    public String toDetailBlock() {
        StringBuilder builder = new StringBuilder();
        builder.append(Displayable.labelled("Service Code", serviceCode)).append(System.lineSeparator());
        builder.append(Displayable.labelled("Service", serviceName)).append(System.lineSeparator());
        builder.append(Displayable.labelled("Type",
                serviceType == null ? null : serviceType.getDisplayName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Activated",
                Displayable.formatDate(activationDate))).append(System.lineSeparator());
        builder.append(Displayable.labelled("Status",
                serviceStatus == null ? null : serviceStatus.getDisplayName()));
        return builder.toString();
    }
}
