package com.amdocs.telecom.model;

import com.amdocs.telecom.model.enums.Role;

/**
 * Shared base for the people and organisations the system deals with:
 * login accounts, customers and network engineers.
 *
 * <p>All three carry a name and an email address, and all three answer to a
 * business identifier and a role. Declaring those as abstract here lets the
 * notification and audit services treat any party uniformly, which is the
 * abstraction and polymorphism the case study asks for.</p>
 */
public abstract class AbstractParty extends TimestampedEntity implements Auditable {

    private static final long serialVersionUID = 1L;

    private String name;
    private String email;

    protected AbstractParty() {
        super();
    }

    protected AbstractParty(Long id, String name, String email) {
        super(id);
        this.name = name;
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * The human readable identifier for this party: a customer number, an
     * employee code or a username, depending on the subclass.
     */
    public abstract String getBusinessKey();

    /**
     * The role this party acts as, which drives role based access control.
     */
    public abstract Role getRole();

    /**
     * Audit rows identify a party by its business key rather than its
     * surrogate id, so the trail stays readable long after the fact.
     */
    @Override
    public String getAuditEntityId() {
        return getBusinessKey();
    }
}
