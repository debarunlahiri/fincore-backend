package com.fincore.core.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fixed deposit pricing: rate bands by tenure, interest compounded quarterly. Pure functions, no
 * I/O.
 */
public final class DepositCalculator {

    public static final BigDecimal MIN_PRINCIPAL = new BigDecimal("1000");
    public static final BigDecimal MAX_PRINCIPAL = new BigDecimal("10000000");
    public static final int MIN_MONTHS = 3;
    public static final int MAX_MONTHS = 120;

    /**
     * Breaking a deposit early costs one percentage point of interest, and pays no interest under 3
     * months.
     */
    private static final BigDecimal PREMATURE_PENALTY = new BigDecimal("1.00");

    private DepositCalculator() {}

    public static BigDecimal rateFor(int tenureMonths) {
        if (tenureMonths <= 6) return new BigDecimal("6.00");
        if (tenureMonths <= 12) return new BigDecimal("6.75");
        if (tenureMonths <= 24) return new BigDecimal("7.00");
        if (tenureMonths <= 60) return new BigDecimal("7.25");
        return new BigDecimal("7.00");
    }

    /** principal * (1 + rate/400) ^ (months/3), rounded to paise. */
    public static BigDecimal maturityAmount(
            BigDecimal principal, BigDecimal annualRatePercent, int months) {
        double quarterlyRate = annualRatePercent.doubleValue() / 400.0;
        double value = principal.doubleValue() * Math.pow(1 + quarterlyRate, months / 3.0);
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal prematurePayout(
            BigDecimal principal, BigDecimal annualRatePercent, int elapsedFullMonths) {
        if (elapsedFullMonths < MIN_MONTHS) {
            return principal.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal reduced = annualRatePercent.subtract(PREMATURE_PENALTY).max(BigDecimal.ZERO);
        return maturityAmount(principal, reduced, elapsedFullMonths);
    }
}
