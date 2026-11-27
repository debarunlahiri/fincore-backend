package com.fincore.core.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record OpenDepositRequest(
        @NotBlank String accountNumber,
        @NotNull @DecimalMin("1000.00") @Digits(integer = 10, fraction = 2) BigDecimal principal,
        @Min(3) @Max(120) int tenureMonths) {}
