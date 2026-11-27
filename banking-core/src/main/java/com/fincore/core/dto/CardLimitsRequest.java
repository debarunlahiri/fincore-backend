package com.fincore.core.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CardLimitsRequest(
        @NotNull @DecimalMin("100.00") @Digits(integer = 7, fraction = 2) BigDecimal dailyLimit,
        boolean onlineEnabled,
        boolean internationalEnabled) {}
