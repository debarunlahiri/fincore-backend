package com.fincore.core.entity;

import com.fincore.common.util.Reference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "card_transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CardTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String reference = Reference.next("CTX");

    @Column(name = "card_id", nullable = false)
    private UUID cardId;

    @Column(nullable = false)
    private String merchant;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String channel;

    /** APPROVED or DECLINED. */
    @Column(nullable = false)
    private String status;

    @Column(name = "decline_reason")
    private String declineReason;

    /**
     * Statement reference of the debit, so the card transaction can be matched to the account
     * statement.
     */
    @Column(name = "journal_reference")
    private String journalReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public CardTransaction(
            UUID cardId,
            String merchant,
            BigDecimal amount,
            String channel,
            String status,
            String declineReason,
            String journalReference) {
        this.cardId = cardId;
        this.merchant = merchant;
        this.amount = amount;
        this.channel = channel;
        this.status = status;
        this.declineReason = declineReason;
        this.journalReference = journalReference;
    }
}
