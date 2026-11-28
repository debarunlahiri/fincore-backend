package com.fincore.core.dto;

import com.fincore.core.service.MerchantAdminService;
import java.math.BigDecimal;

public record AdminMerchantSummaryResponse(
        long totalMerchants,
        long activeMerchants,
        long suspendedMerchants,
        long businessPlanMerchants,
        long outlets,
        long onSettlementHold,
        BigDecimal royaltyOnHold,
        BigDecimal todayVolume,
        BigDecimal monthVolume,
        BigDecimal feesEarnedThisMonth,
        BigDecimal pendingSettlement) {
    public static AdminMerchantSummaryResponse from(MerchantAdminService.Summary s) {
        return new AdminMerchantSummaryResponse(
                s.total(),
                s.active(),
                s.suspended(),
                s.businessPlan(),
                s.outlets(),
                s.onSettlementHold(),
                s.royaltyOnHold(),
                s.todayVolume(),
                s.monthVolume(),
                s.feesEarnedMonth(),
                s.pendingSettlement());
    }
}
