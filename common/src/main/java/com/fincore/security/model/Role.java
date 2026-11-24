package com.fincore.security.model;

/**
 * CUSTOMER is an end user. The other three are staff roles for the admin APIs:
 *
 * <ul>
 *   <li>ADMIN: superuser, every admin API including staff management
 *   <li>OPERATIONS: changes state (freeze/unfreeze, outbox replay, run standing instructions)
 *   <li>COMPLIANCE: read-only oversight (audit log, reconciliation, outbox health)
 * </ul>
 */
public enum Role {
    CUSTOMER,
    ADMIN,
    OPERATIONS,
    COMPLIANCE;

    public boolean isStaff() {
        return this != CUSTOMER;
    }
}
