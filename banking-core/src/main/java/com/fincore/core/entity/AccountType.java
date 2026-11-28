package com.fincore.core.entity;

public enum AccountType {
    SAVINGS,
    CURRENT,
    /** Receives merchant settlements; created only through the merchant service. */
    MERCHANT,
    /** Internal settlement account; the only account type allowed to go negative. */
    SYSTEM,
    /** Counter-party of money that arrives from outside (guest payments through the gateway). */
    GATEWAY
}
