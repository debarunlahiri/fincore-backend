package com.fincore.core.dto;

import com.fincore.core.entity.MerchantSettlement;
import java.math.BigDecimal;
import java.time.Instant;

public record SettlementResponse(
        String reference,
        int paymentCount,
        BigDecimal grossAmount,
        BigDecimal feeAmount,
        BigDecimal royaltyAmount,
        BigDecimal royaltyHeld,
        BigDecimal netAmount,
        String triggeredBy,
        String statementReference,
        Instant createdAt) {
    public static SettlementResponse from(MerchantSettlement s) {
        return new SettlementResponse(
                s.getReference(),
                s.getPaymentCount(),
                s.getGrossAmount(),
                s.getFeeAmount(),
                s.getRoyaltyAmount(),
                s.getRoyaltyHeld(),
                s.getNetAmount(),
                s.getTriggeredBy(),
                s.getJournalReference(),
                s.getCreatedAt());
    }
}
