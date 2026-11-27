package com.fincore.core.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Payload of the {@code TransferCompleted} outbox event. */
public record TransferCompleted(
        UUID transferId,
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        String currency,
        UUID journalId,
        Instant occurredAt) {}
