package com.fincore.core.dto;

import com.fincore.core.entity.CardTransaction;
import java.math.BigDecimal;
import java.time.Instant;

public record CardTransactionResponse(
        String reference,
        String merchant,
        BigDecimal amount,
        String channel,
        String status,
        String declineReason,
        String statementReference,
        Instant createdAt) {
    public static CardTransactionResponse from(CardTransaction t) {
        return new CardTransactionResponse(
                t.getReference(),
                t.getMerchant(),
                t.getAmount(),
                t.getChannel(),
                t.getStatus(),
                t.getDeclineReason(),
                t.getJournalReference(),
                t.getCreatedAt());
    }
}
