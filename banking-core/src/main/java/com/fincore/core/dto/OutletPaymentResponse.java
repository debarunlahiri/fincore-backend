package com.fincore.core.dto;

import com.fincore.core.entity.MerchantPayment;
import java.math.BigDecimal;
import java.time.Instant;

/** What a main merchant sees of an outlet's payment: amount and its own royalty, nothing about the payer. */
public record OutletPaymentResponse(
        String reference,
        BigDecimal amount,
        BigDecimal royalty,
        String status,
        boolean settled,
        Instant createdAt) {
    public static OutletPaymentResponse from(MerchantPayment p) {
        return new OutletPaymentResponse(
                p.getReference(),
                p.getAmount(),
                p.getRoyalty(),
                p.getStatus(),
                p.isSettled(),
                p.getCreatedAt());
    }
}
