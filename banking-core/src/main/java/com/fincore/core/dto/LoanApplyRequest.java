package com.fincore.core.dto;

import com.fincore.core.entity.LoanType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record LoanApplyRequest(
        @NotBlank String accountNumber,
        @NotNull LoanType type,
        @NotNull @DecimalMin("10000.00") @Digits(integer = 10, fraction = 2) BigDecimal principal,
        @Min(6) int tenureMonths,
        @NotNull @DecimalMin("1.00") @Digits(integer = 10, fraction = 2)
                BigDecimal monthlyIncome) {}
