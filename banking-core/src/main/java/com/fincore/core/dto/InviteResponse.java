package com.fincore.core.dto;

import com.fincore.core.entity.MerchantInvite;
import java.math.BigDecimal;
import java.time.Instant;

public record InviteResponse(
        String inviteCode,
        BigDecimal royaltyPercent,
        String note,
        String status,
        Instant expiresAt,
        Instant createdAt) {
    public static InviteResponse from(MerchantInvite i) {
        String status =
                MerchantInvite.PENDING.equals(i.getStatus()) && !i.isUsable()
                        ? "EXPIRED"
                        : i.getStatus();
        return new InviteResponse(
                i.getReference(),
                i.getRoyaltyPercent(),
                i.getNote(),
                status,
                i.getExpiresAt(),
                i.getCreatedAt());
    }
}
