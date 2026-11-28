package com.fincore.core.dto;

import com.fincore.core.entity.MerchantRole;
import jakarta.validation.constraints.NotNull;

public record ChangeMemberRoleRequest(@NotNull MerchantRole role) {}
