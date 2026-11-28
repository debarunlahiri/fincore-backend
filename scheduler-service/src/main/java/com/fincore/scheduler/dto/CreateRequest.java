package com.fincore.scheduler.dto;

import com.fincore.scheduler.entity.Frequency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateRequest(
    @NotBlank String sourceAccountNumber,
    @NotBlank String targetAccountNumber,
    @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
    @NotNull Frequency frequency,
    @NotNull LocalDate firstRunDate,
    @Size(max = 140) String remarks) {
}
