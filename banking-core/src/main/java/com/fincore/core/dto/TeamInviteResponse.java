package com.fincore.core.dto;

import com.fincore.core.entity.MerchantRole;
import com.fincore.core.entity.MerchantTeamInvite;
import java.time.Instant;

public record TeamInviteResponse(
        String inviteCode,
        MerchantRole role,
        String label,
        String status,
        Instant expiresAt,
        Instant createdAt) {
    public static TeamInviteResponse from(MerchantTeamInvite i) {
        String status =
                MerchantTeamInvite.PENDING.equals(i.getStatus()) && !i.isUsable()
                        ? "EXPIRED"
                        : i.getStatus();
        return new TeamInviteResponse(
                i.getReference(), i.getRole(), i.getLabel(), status, i.getExpiresAt(), i.getCreatedAt());
    }
}
