package com.fincore.ledger.dto;

import com.fincore.ledger.entity.Direction;
import com.fincore.ledger.entity.LedgerEntry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

public record EntryResponse(
        UUID id, UUID journalId, Direction direction, BigDecimal amount, BigDecimal balanceAfter, Instant createdAt) {

    public static EntryResponse from(LedgerEntry e) {
        return new EntryResponse(
                e.getId(),
                e.getJournalId(),
                e.getDirection(),
                e.getAmount().setScale(2, RoundingMode.UNNECESSARY),
                e.getBalanceAfter().setScale(2, RoundingMode.UNNECESSARY),
                e.getCreatedAt());
    }
}
