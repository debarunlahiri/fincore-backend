package com.fincore.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record KycRequest(
        @NotBlank @Pattern(regexp = "PAN|AADHAAR|PASSPORT") String documentType,
        @NotBlank String documentNumber) {}
