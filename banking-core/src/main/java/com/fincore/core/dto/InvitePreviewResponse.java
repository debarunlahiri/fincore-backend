package com.fincore.core.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** The terms a prospective franchisee sees before joining. */
public record InvitePreviewResponse(
        String mainMerchantName,
        String category,
        BigDecimal royaltyPercent,
        String note,
        Instant expiresAt) {}
