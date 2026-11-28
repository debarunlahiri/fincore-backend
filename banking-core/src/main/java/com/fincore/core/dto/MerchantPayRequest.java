package com.fincore.core.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record MerchantPayRequest(
        @NotBlank @Size(max = 20) String accountNumber,
        @NotNull @DecimalMin("1.00") @Digits(integer = 9, fraction = 2) BigDecimal amount,
        @Size(max = 140) String note,
        /** Signature from a fixed-amount payment link; when present the amount must match it. */
        @Size(max = 64) String sig,
        /** Tried in order when the first account cannot pay (low balance, frozen, daily limit). */
        @Size(max = 2) List<@NotBlank @Size(max = 20) String> backupAccountNumbers) {}
