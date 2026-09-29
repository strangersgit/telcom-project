package com.amdocs.telecom.model;

import com.amdocs.telecom.model.enums.CustomerStatus;
import com.amdocs.telecom.model.enums.CustomerType;
import com.amdocs.telecom.model.enums.Region;
import com.amdocs.telecom.model.enums.Role;

/**
 * A subscriber, from a single consumer handset up to an enterprise with
 * several sites. Described in section 3 of the case study.
 */
public class Customer extends AbstractParty {

    private static final long serialVersionUID = 1L;

    private String customerNumber;
    private String mobileNumber;
    private CustomerType customerType;
    private String city;
    private Region region;
    private CustomerStatus status = CustomerStatus.ACTIVE;
    private Long userId;

    public Customer() {
        super();
    }

    public Customer(String customerNumber, String customerName, String email,
                    String mobileNumber, CustomerType customerType, String city, Region region) {
        super(null, customerName, email);
        this.customerNumber = customerNumber;
        this.mobileNumber = mobileNumber;
        this.customerType = customerType;
        this.city = city;
        this.region = region;
    }

    public String getCustomerNumber() {
        return customerNumber;
    }

    public void setCustomerNumber(String customerNumber) {
        this.customerNumber = customerNumber;
    }

    /**
     * Alias for the inherited name, matching the case study's field naming.
     */
    public String getCustomerName() {
        return getName();
    }

    public void setCustomerName(String customerName) {
        setName(customerName);
    }

    public String getMobileNumber() {
        return mobileNumber;
    }

    public void setMobileNumber(String mobileNumber) {
        this.mobileNumber = mobileNumber;
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

    public CustomerStatus getStatus() {
        return status;
    }

    public void setStatus(CustomerStatus status) {
        this.status = status;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /**
     * Only an active customer may open a ticket.
     */
    public boolean canRaiseTicket() {
        return status != null && status.canRaiseTicket();
    }

    /**
     * Enterprise incidents outrank consumer incidents when priorities tie.
     */
    public int getTierWeight() {
        return customerType == null ? 0 : customerType.getTierWeight();
    }

    @Override
    public Role getRole() {
        return Role.CUSTOMER;
    }

    @Override
    public String getBusinessKey() {
        return customerNumber;
    }

    @Override
    public String getAuditEntityType() {
        return "CUSTOMER";
    }

    @Override
    public String toSummaryLine() {
        return String.format("%-12s %-26s %-12s %-14s %-10s",
                Displayable.orDash(customerNumber),
                Displayable.truncate(getName(), 26),
                customerType == null ? "-" : customerType.getDisplayName(),
                Displayable.orDash(city),
                status == null ? "-" : status.getDisplayName());
    }

    @Override
    public String toDetailBlock() {
        StringBuilder builder = new StringBuilder();
        builder.append(Displayable.labelled("Customer No", customerNumber)).append(System.lineSeparator());
        builder.append(Displayable.labelled("Name", getName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Type",
                customerType == null ? null : customerType.getDisplayName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Email", getEmail())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Mobile", mobileNumber)).append(System.lineSeparator());
        builder.append(Displayable.labelled("City", city)).append(System.lineSeparator());
        builder.append(Displayable.labelled("Region",
                region == null ? null : region.getDisplayName())).append(System.lineSeparator());
        builder.append(Displayable.labelled("Status",
                status == null ? null : status.getDisplayName()));
        return builder.toString();
    }
}
