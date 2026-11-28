package com.fincore.core.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Merchant plans. STARTER charges nothing per payment but has tight limits; BUSINESS charges a
 * percentage of every payment and has much higher limits. The limits live in code so that the
 * rules are versioned with the application; the merchant row only stores which plan it is on.
 */
public enum MerchantPlan {
    STARTER(
            "Starter",
            "0.00",
            "10000.00",
            "50000.00",
            "200000.00",
            50,
            2,
            0,
            2),
    BUSINESS(
            "Business",
            "1.50",
            "200000.00",
            "2000000.00",
            "10000000.00",
            2000,
            0,
            50,
            10);

    private final String displayName;
    private final BigDecimal feePercent;
    private final BigDecimal perTransactionLimit;
    private final BigDecimal dailyVolumeLimit;
    private final BigDecimal monthlyVolumeLimit;
    private final int dailyTransactionLimit;
    private final int settlementDelayDays;
    private final int maxSubMerchants;
    private final int maxTeamMembers;

    MerchantPlan(
            String displayName,
            String feePercent,
            String perTransactionLimit,
            String dailyVolumeLimit,
            String monthlyVolumeLimit,
            int dailyTransactionLimit,
            int settlementDelayDays,
            int maxSubMerchants,
            int maxTeamMembers) {
        this.displayName = displayName;
        this.feePercent = new BigDecimal(feePercent);
        this.perTransactionLimit = new BigDecimal(perTransactionLimit);
        this.dailyVolumeLimit = new BigDecimal(dailyVolumeLimit);
        this.monthlyVolumeLimit = new BigDecimal(monthlyVolumeLimit);
        this.dailyTransactionLimit = dailyTransactionLimit;
        this.settlementDelayDays = settlementDelayDays;
        this.maxSubMerchants = maxSubMerchants;
        this.maxTeamMembers = maxTeamMembers;
    }

    public String displayName() {
        return displayName;
    }

    public BigDecimal feePercent() {
        return feePercent;
    }

    public BigDecimal perTransactionLimit() {
        return perTransactionLimit;
    }

    public BigDecimal dailyVolumeLimit() {
        return dailyVolumeLimit;
    }

    public BigDecimal monthlyVolumeLimit() {
        return monthlyVolumeLimit;
    }

    public int dailyTransactionLimit() {
        return dailyTransactionLimit;
    }

    /** Payments become eligible for settlement this many days after they were taken. */
    public int settlementDelayDays() {
        return settlementDelayDays;
    }

    /** Franchise outlets (sub-merchants) this plan may have; 0 means the plan has no franchise network. */
    public int maxSubMerchants() {
        return maxSubMerchants;
    }

    /** Team members besides the owner. */
    public int maxTeamMembers() {
        return maxTeamMembers;
    }

    /** The charge the merchant pays on a payment of this size, rounded to paise. */
    public BigDecimal feeFor(BigDecimal amount) {
        return amount.multiply(feePercent)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }
}
