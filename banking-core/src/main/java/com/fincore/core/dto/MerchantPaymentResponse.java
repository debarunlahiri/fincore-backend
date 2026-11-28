package com.fincore.core.dto;

import com.fincore.core.entity.MerchantPayment;
import java.math.BigDecimal;
import java.time.Instant;

/** A payment as the merchant sees it. */
public record MerchantPaymentResponse(
        String reference,
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal royalty,
        BigDecimal netAmount,
        String status,
        boolean settled,
        String payerAccount,
        String note,
        String statementReference,
        Instant createdAt,
        Instant refundedAt) {
    public static MerchantPaymentResponse from(MerchantPayment p) {
        return new MerchantPaymentResponse(
                p.getReference(),
                p.getAmount(),
                p.getFee(),
                p.getRoyalty(),
                p.getNetAmount(),
                p.getStatus(),
                p.isSettled(),
                p.getPayerAccountMasked(),
                p.getNote(),
                p.getJournalReference(),
                p.getCreatedAt(),
                p.getRefundedAt());
    }
}
