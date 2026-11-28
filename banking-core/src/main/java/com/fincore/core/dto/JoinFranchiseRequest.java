package com.fincore.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinFranchiseRequest(
        @NotBlank @Size(max = 30) String inviteCode,
        @NotBlank @Size(min = 2, max = 100) String businessName) {}
