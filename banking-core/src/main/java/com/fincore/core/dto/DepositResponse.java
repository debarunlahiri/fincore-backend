package com.fincore.core.dto;

import com.fincore.core.entity.FixedDeposit;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record DepositResponse(
        String reference,
        String accountNumber,
        BigDecimal principal,
        BigDecimal annualRate,
        int tenureMonths,
        LocalDate openedOn,
        LocalDate maturityDate,
        BigDecimal maturityAmount,
        String status,
        BigDecimal payoutAmount,
        Instant closedAt) {
    public static DepositResponse from(FixedDeposit d) {
        return new DepositResponse(
                d.getReference(),
                d.getAccountNumber(),
                d.getPrincipal(),
                d.getAnnualRate(),
                d.getTenureMonths(),
                d.getOpenedOn(),
                d.getMaturityDate(),
                d.getMaturityAmount(),
                d.getStatus().name(),
                d.getPayoutAmount(),
                d.getClosedAt());
    }
}
