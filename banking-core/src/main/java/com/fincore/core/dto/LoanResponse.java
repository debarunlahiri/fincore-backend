package com.fincore.core.dto;

import com.fincore.core.entity.Loan;
import com.fincore.core.entity.LoanType;
import java.math.BigDecimal;
import java.time.Instant;

public record LoanResponse(
        String reference,
        LoanType type,
        String accountNumber,
        BigDecimal principal,
        BigDecimal annualRate,
        int tenureMonths,
        BigDecimal emi,
        BigDecimal processingFee,
        String status,
        String rejectionReason,
        BigDecimal outstandingPrincipal,
        Instant disbursedAt,
        Instant createdAt) {
    public static LoanResponse from(Loan l) {
        return new LoanResponse(
                l.getReference(),
                l.getType(),
                l.getAccountNumber(),
                l.getPrincipal(),
                l.getAnnualRate(),
                l.getTenureMonths(),
                l.getEmi(),
                l.getProcessingFee(),
                l.getStatus().name(),
                l.getRejectionReason(),
                l.getOutstandingPrincipal(),
                l.getDisbursedAt(),
                l.getCreatedAt());
    }
}
