package com.fincore.payment.dto;

import com.fincore.payment.entity.Payment;
import com.fincore.payment.entity.PaymentStatus;
import com.fincore.payment.entity.Rail;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** Only the reference number and the account number are exposed, never internal ids. */
public record PaymentResponse(
        String reference,
        String accountNumber,
        Rail rail,
        String beneficiaryRef,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        String failureReason,
        Instant createdAt) {
    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(
                p.getReference(),
                p.getAccountNumber(),
                p.getRail(),
                p.getBeneficiaryRef(),
                p.getAmount().setScale(2, RoundingMode.UNNECESSARY),
                p.getCurrency(),
                p.getStatus(),
                p.getFailureReason(),
                p.getCreatedAt());
    }
}
