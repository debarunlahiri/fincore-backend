package com.fincore.core.dto;

import com.fincore.core.entity.Transfer;
import com.fincore.core.entity.TransferStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Receipt view of a transfer. Only the reference number and account numbers are exposed, never
 * internal ids.
 */
public record TransferResponse(
        String reference,
        String sourceAccountNumber,
        String targetAccountNumber,
        BigDecimal amount,
        String currency,
        TransferStatus status,
        String remarks,
        Instant createdAt) {
    public static TransferResponse from(Transfer t) {
        return new TransferResponse(
                t.getReference(),
                t.getSourceAccountNumber(),
                t.getTargetAccountNumber(),
                t.getAmount().setScale(2, RoundingMode.UNNECESSARY),
                t.getCurrency(),
                t.getStatus(),
                t.getRemarks(),
                t.getCreatedAt());
    }
}
