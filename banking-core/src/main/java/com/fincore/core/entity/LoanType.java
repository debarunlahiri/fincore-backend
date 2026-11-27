package com.fincore.core.entity;

import java.math.BigDecimal;

/**
 * Product terms: indicative annual rate, maximum amount, allowed tenure, processing fee percent.
 */
public enum LoanType {
    PERSONAL("12.50", 1_000_000, 6, 60, "1.00"),
    HOME("8.75", 10_000_000, 60, 360, "0.50"),
    AUTO("9.50", 2_500_000, 12, 84, "1.00"),
    EDUCATION("9.00", 2_000_000, 12, 120, "0.50");

    public static final BigDecimal MIN_PRINCIPAL = new BigDecimal("10000");

    private final BigDecimal rate;
    private final BigDecimal maxPrincipal;
    private final int minMonths;
    private final int maxMonths;
    private final BigDecimal feePercent;

    LoanType(String rate, long maxPrincipal, int minMonths, int maxMonths, String feePercent) {
        this.rate = new BigDecimal(rate);
        this.maxPrincipal = BigDecimal.valueOf(maxPrincipal);
        this.minMonths = minMonths;
        this.maxMonths = maxMonths;
        this.feePercent = new BigDecimal(feePercent);
    }

    public BigDecimal rate() {
        return rate;
    }

    public BigDecimal maxPrincipal() {
        return maxPrincipal;
    }

    public int minMonths() {
        return minMonths;
    }

    public int maxMonths() {
        return maxMonths;
    }

    public BigDecimal feePercent() {
        return feePercent;
    }
}
