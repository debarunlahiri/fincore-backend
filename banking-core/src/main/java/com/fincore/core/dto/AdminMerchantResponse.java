package com.fincore.core.dto;

import com.fincore.core.entity.MerchantPlan;
import com.fincore.core.entity.MerchantStatus;
import com.fincore.core.service.MerchantAdminService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** Staff view of one merchant. The owner's internal user id is deliberately left out. */
public record AdminMerchantResponse(
        String merchantCode,
        String businessName,
        String category,
        MerchantStatus status,
        String role,
        MerchantPlan plan,
        String mainMerchantCode,
        String mainMerchantName,
        BigDecimal royaltyPercent,
        String accountNumber,
        BigDecimal balance,
        BigDecimal royaltyOnHold,
        int settlementFailures,
        String settlementIssue,
        Instant createdAt) {
    public static AdminMerchantResponse from(MerchantAdminService.Row r) {
        var m = r.merchant();
        return new AdminMerchantResponse(
                m.getReference(),
                m.getBusinessName(),
                m.getCategory(),
                m.getStatus(),
                m.isSubMerchant() ? "OUTLET" : "MERCHANT",
                r.parent() == null ? m.getPlan() : r.parent().getPlan(),
                r.parent() == null ? null : r.parent().getReference(),
                r.parent() == null ? null : r.parent().getBusinessName(),
                m.getRoyaltyPercent(),
                m.getAccountNumber(),
                r.account() == null
                        ? null
                        : r.account().getBalance().setScale(2, RoundingMode.HALF_UP),
                m.getRoyaltyOwed(),
                m.getSettlementFailures(),
                m.getSettlementError(),
                m.getCreatedAt());
    }
}
