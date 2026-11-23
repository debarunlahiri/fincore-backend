package com.fincore.payment.event;

import com.fincore.payment.entity.Payment;
import com.fincore.payment.entity.PaymentStatus;
import com.fincore.payment.entity.Rail;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Payload shared by the PaymentInitiated, PaymentSettled and PaymentRefunded outbox events. */
public record PaymentEvent(
        UUID paymentId,
        UUID accountId,
        Rail rail,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        String reason,
        Instant occurredAt) {

    public static PaymentEvent of(Payment p) {
        return new PaymentEvent(
                p.getId(),
                p.getAccountId(),
                p.getRail(),
                p.getAmount(),
                p.getCurrency(),
                p.getStatus(),
                p.getFailureReason(),
                Instant.now());
    }
}
