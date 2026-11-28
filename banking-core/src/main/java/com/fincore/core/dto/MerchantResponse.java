package com.fincore.core.dto;

import com.fincore.core.entity.Merchant;
import com.fincore.core.entity.MerchantPermission;
import com.fincore.core.entity.MerchantPlan;
import com.fincore.core.entity.MerchantRole;
import com.fincore.core.entity.MerchantStatus;
import com.fincore.core.service.MerchantService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * The merchant's own dashboard. {@code role} is MAIN (can have outlets), OUTLET (franchise outlet of
 * a main merchant, plan and limits come from it) or STANDALONE.
 */
public record MerchantResponse(
        String merchantCode,
        String businessName,
        String category,
        MerchantStatus status,
        String role,
        MerchantPlan plan,
        MerchantPlanResponse limits,
        String accountNumber,
        BigDecimal balance,
        String mainMerchantCode,
        String mainMerchantName,
        BigDecimal royaltyPercent,
        long outletCount,
        BigDecimal todayVolume,
        long todayCount,
        BigDecimal monthVolume,
        BigDecimal pendingSettlement,
        long pendingPayments,
        BigDecimal royaltyOnHold,
        String settlementIssue,
        Instant settlementRetryAt,
        MerchantRole teamRole,
        String teamRoleName,
        List<MerchantPermission> permissions,
        Instant createdAt) {

    public static MerchantResponse from(MerchantService.Overview o) {
        Merchant m = o.merchant();
        // money and the settlement account are only for roles that may see settlements
        boolean money = o.role().can(MerchantPermission.VIEW_SETTLEMENTS);
        String role = m.isSubMerchant() ? "OUTLET" : o.outletCount() > 0 ? "MAIN" : "STANDALONE";
        return new MerchantResponse(
                m.getReference(),
                m.getBusinessName(),
                m.getCategory(),
                m.getStatus(),
                role,
                o.plan(),
                MerchantPlanResponse.from(o.plan()),
                money ? m.getAccountNumber() : null,
                money ? o.account().getBalance().setScale(2, RoundingMode.HALF_UP) : null,
                o.parent() == null ? null : o.parent().getReference(),
                o.parent() == null ? null : o.parent().getBusinessName(),
                m.getRoyaltyPercent(),
                o.outletCount(),
                o.todayVolume(),
                o.todayCount(),
                o.monthVolume(),
                money ? o.pendingNet() : null,
                o.pendingCount(),
                money ? m.getRoyaltyOwed() : null,
                m.getSettlementError(),
                m.getSettlementRetryAt(),
                o.role(),
                o.role().displayName(),
                o.role().permissions().stream().sorted().toList(),
                m.getCreatedAt());
    }
}
