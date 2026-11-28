package com.fincore.core.dto;

import com.fincore.core.entity.MerchantStatus;
import com.fincore.core.service.MerchantService;
import java.math.BigDecimal;
import java.time.Instant;

public record OutletResponse(
        String merchantCode,
        String businessName,
        MerchantStatus status,
        BigDecimal royaltyPercent,
        BigDecimal todayVolume,
        BigDecimal monthVolume,
        BigDecimal royaltyEarned,
        Instant joinedAt) {
    public static OutletResponse from(MerchantService.OutletView v) {
        return new OutletResponse(
                v.outlet().getReference(),
                v.outlet().getBusinessName(),
                v.outlet().getStatus(),
                v.outlet().getRoyaltyPercent(),
                v.todayVolume(),
                v.monthVolume(),
                v.royaltyEarned(),
                v.outlet().getCreatedAt());
    }
}
