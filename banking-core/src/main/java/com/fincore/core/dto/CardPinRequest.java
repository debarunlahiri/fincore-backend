package com.fincore.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CardPinRequest(
        @NotBlank @Pattern(regexp = "\\d{4}", message = "PIN must be 4 digits") String pin) {}
