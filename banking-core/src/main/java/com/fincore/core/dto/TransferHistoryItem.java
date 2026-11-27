package com.fincore.core.dto;

import com.fincore.core.entity.Transfer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/** One row of the transaction history. {@code direction} is relative to the signed-in user. */
public record TransferHistoryItem(
        String reference,
        String direction,
        String fromAccountNumber,
        String toAccountNumber,
        BigDecimal amount,
        String currency,
        String status,
        String remarks,
        Instant createdAt) {
    public static TransferHistoryItem from(Transfer t, UUID userId) {
        return new TransferHistoryItem(
                t.getReference(),
                t.getInitiatedBy().equals(userId) ? "SENT" : "RECEIVED",
                t.getSourceAccountNumber(),
                t.getTargetAccountNumber(),
                t.getAmount().setScale(2, RoundingMode.UNNECESSARY),
                t.getCurrency(),
                t.getStatus().name(),
                t.getRemarks(),
                t.getCreatedAt());
    }
}
