package com.fincore.core.dto;

import com.fincore.core.entity.Card;
import java.math.BigDecimal;
import java.time.Instant;

/** Masked view; the card number is never returned again after issue. */
public record CardResponse(
        String reference,
        String kind,
        String maskedNumber,
        String nameOnCard,
        String expiry,
        String accountNumber,
        String status,
        BigDecimal dailyLimit,
        boolean onlineEnabled,
        boolean internationalEnabled,
        boolean pinSet,
        Instant createdAt) {
    public static CardResponse from(Card c) {
        return new CardResponse(
                c.getReference(),
                c.getKind(),
                "**** **** **** " + c.getLast4(),
                c.getHolderName(),
                String.format("%02d/%02d", c.getExpiryMonth(), c.getExpiryYear() % 100),
                c.getAccountNumber(),
                c.getStatus(),
                c.getDailyLimit(),
                c.isOnlineEnabled(),
                c.isInternationalEnabled(),
                c.getPinHash() != null,
                c.getCreatedAt());
    }
}
