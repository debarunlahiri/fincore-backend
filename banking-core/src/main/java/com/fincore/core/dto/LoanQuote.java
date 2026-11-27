package com.fincore.core.dto;

import com.fincore.core.entity.LoanType;
import java.math.BigDecimal;

/** Indicative terms before applying. */
public record LoanQuote(
        LoanType type,
        BigDecimal principal,
        int tenureMonths,
        BigDecimal annualRate,
        BigDecimal emi,
        BigDecimal processingFee,
        BigDecimal totalInterest,
        BigDecimal totalPayable) {}
