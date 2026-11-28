package com.fincore.core.dto;

import java.math.BigDecimal;
import java.util.List;

/** The franchise network of a main merchant. */
public record NetworkResponse(
        BigDecimal totalRoyaltyEarned,
        int maxOutlets,
        List<OutletResponse> outlets,
        List<InviteResponse> invites) {}
