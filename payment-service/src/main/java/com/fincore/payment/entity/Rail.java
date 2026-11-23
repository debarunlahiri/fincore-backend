package com.fincore.payment.entity;

import java.math.BigDecimal;

/** Simulated payment rails with their per-payment ceilings. */
public enum Rail {
    UPI(new BigDecimal("100000")),
    IMPS(new BigDecimal("500000")),
    NEFT(new BigDecimal("1000000"));

    private final BigDecimal maxAmount;

    Rail(BigDecimal maxAmount) {
        this.maxAmount = maxAmount;
    }

    public BigDecimal maxAmount() {
        return maxAmount;
    }
}
