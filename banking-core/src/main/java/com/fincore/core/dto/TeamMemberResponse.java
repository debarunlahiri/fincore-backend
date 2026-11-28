package com.fincore.core.dto;

import com.fincore.core.entity.MerchantMember;
import com.fincore.core.entity.MerchantRole;
import java.time.Instant;

public record TeamMemberResponse(
        String memberCode, String label, MerchantRole role, Instant joinedAt) {
    public static TeamMemberResponse from(MerchantMember m) {
        return new TeamMemberResponse(m.getReference(), m.getLabel(), m.getRole(), m.getCreatedAt());
    }
}
