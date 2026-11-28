package com.fincore.core.dto;

import com.fincore.core.entity.MerchantPlan;
import java.math.BigDecimal;

public record MerchantPlanResponse(
        MerchantPlan plan,
        String name,
        BigDecimal feePercent,
        BigDecimal perTransactionLimit,
        BigDecimal dailyVolumeLimit,
        BigDecimal monthlyVolumeLimit,
        int dailyTransactionLimit,
        int settlementDelayDays,
        int maxOutlets,
        int maxTeamMembers) {
    public static MerchantPlanResponse from(MerchantPlan p) {
        return new MerchantPlanResponse(
                p,
                p.displayName(),
                p.feePercent(),
                p.perTransactionLimit(),
                p.dailyVolumeLimit(),
                p.monthlyVolumeLimit(),
                p.dailyTransactionLimit(),
                p.settlementDelayDays(),
                p.maxSubMerchants(),
                p.maxTeamMembers());
    }
}
