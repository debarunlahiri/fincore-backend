package com.fincore.core.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record GuestOrderRequest(
        @NotNull @DecimalMin("1.00") @Digits(integer = 9, fraction = 2) BigDecimal amount,
        /** Signature from a fixed-amount payment link; when present the amount must match it. */
        @Size(max = 64) String sig,
        @NotBlank @Size(max = 80) String name,
        @NotBlank @Email @Size(max = 254) String email,
        @Size(max = 140) String note) {}
