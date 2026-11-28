package com.fincore.core.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreateInviteRequest(
        @NotNull @DecimalMin("0.00") @DecimalMax("30.00") @Digits(integer = 2, fraction = 2)
                BigDecimal royaltyPercent,
        @Size(max = 140) String note) {}
