package com.fincore.core.dto;

import com.fincore.core.entity.Direction;
import com.fincore.core.entity.Journal;
import com.fincore.core.entity.LedgerEntry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * A statement line. {@code reference} is the journal's customer-facing number, identical on both
 * sides of a transfer.
 */
public record EntryResponse(
        String reference,
        String description,
        Direction direction,
        BigDecimal amount,
        BigDecimal balanceAfter,
        Instant createdAt) {
    public static EntryResponse from(LedgerEntry e, Journal journal) {
        return new EntryResponse(
                journal.getReference(),
                journal.getDescription(),
                e.getDirection(),
                e.getAmount().setScale(2, RoundingMode.UNNECESSARY),
                e.getBalanceAfter().setScale(2, RoundingMode.UNNECESSARY),
                e.getCreatedAt());
    }
}
