package com.fincore.core.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DepositQuote(
        BigDecimal principal,
        int tenureMonths,
        BigDecimal annualRate,
        LocalDate maturityDate,
        BigDecimal maturityAmount,
        BigDecimal interestEarned) {}
