package com.fincore.transfer.dto;

import com.fincore.transfer.entity.Transfer;
import com.fincore.transfer.entity.TransferStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        String currency,
        TransferStatus status,
        String remarks,
        UUID journalId,
        Instant createdAt) {

    public static TransferResponse from(Transfer t) {
        return new TransferResponse(
                t.getId(),
                t.getSourceAccountId(),
                t.getTargetAccountId(),
                t.getAmount().setScale(2, RoundingMode.UNNECESSARY),
                t.getCurrency(),
                t.getStatus(),
                t.getRemarks(),
                t.getJournalId(),
                t.getCreatedAt());
    }
}
