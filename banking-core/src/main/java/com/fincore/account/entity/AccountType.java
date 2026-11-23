package com.fincore.account.entity;

public enum AccountType {
    SAVINGS,
    CURRENT,
    /** Internal settlement account; the only account type allowed to go negative. */
    SYSTEM
}
