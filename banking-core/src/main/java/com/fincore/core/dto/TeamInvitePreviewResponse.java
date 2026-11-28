package com.fincore.core.dto;

import com.fincore.core.entity.MerchantRole;
import java.time.Instant;
import java.util.List;

/** What a person sees before joining a merchant's team. */
public record TeamInvitePreviewResponse(
        String businessName,
        MerchantRole role,
        String roleName,
        List<String> permissions,
        Instant expiresAt) {}
