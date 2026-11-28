package com.fincore.core.dto;

import com.fincore.core.entity.MerchantRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTeamInviteRequest(
        @NotNull MerchantRole role, @NotBlank @Size(min = 2, max = 60) String label) {}
