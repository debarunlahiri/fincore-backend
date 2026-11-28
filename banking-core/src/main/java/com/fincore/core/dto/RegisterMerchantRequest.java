package com.fincore.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterMerchantRequest(
        @NotBlank @Size(min = 2, max = 100) String businessName,
        @NotBlank @Pattern(regexp = "RETAIL|FOOD|SERVICES|EDUCATION|HEALTH|ONLINE|OTHER")
                String category) {}
