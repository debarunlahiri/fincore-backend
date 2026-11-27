package com.fincore.core.dto;

import com.fincore.core.entity.LoanInstallment;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record InstallmentResponse(
        int number,
        LocalDate dueDate,
        BigDecimal emi,
        BigDecimal principalPart,
        BigDecimal interestPart,
        BigDecimal lateFee,
        String status,
        Instant paidAt) {
    public static InstallmentResponse from(LoanInstallment i) {
        return new InstallmentResponse(
                i.getNumber(),
                i.getDueDate(),
                i.getEmi(),
                i.getPrincipalPart(),
                i.getInterestPart(),
                i.getLateFee(),
                i.getStatus().name(),
                i.getPaidAt());
    }
}
