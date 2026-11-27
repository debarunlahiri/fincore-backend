package com.fincore.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "transfers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "initiated_by", nullable = false)
    private UUID initiatedBy;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "target_account_id", nullable = false)
    private UUID targetAccountId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status = TransferStatus.COMPLETED;

    private String remarks;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "journal_id", nullable = false)
    private UUID journalId;

    /** Customer-facing receipt number; equal to the journal reference shown on both statements. */
    @Column(nullable = false, updatable = false, unique = true)
    private String reference;

    @Column(name = "source_account_number", nullable = false)
    private String sourceAccountNumber;

    @Column(name = "target_account_number", nullable = false)
    private String targetAccountNumber;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Transfer(
            String idempotencyKey,
            UUID initiatedBy,
            UUID sourceAccountId,
            UUID targetAccountId,
            BigDecimal amount,
            String currency,
            String remarks,
            String requestHash,
            UUID journalId,
            String reference,
            String sourceAccountNumber,
            String targetAccountNumber) {
        this.idempotencyKey = idempotencyKey;
        this.initiatedBy = initiatedBy;
        this.sourceAccountId = sourceAccountId;
        this.targetAccountId = targetAccountId;
        this.amount = amount;
        this.currency = currency;
        this.remarks = remarks;
        this.requestHash = requestHash;
        this.journalId = journalId;
        this.reference = reference;
        this.sourceAccountNumber = sourceAccountNumber;
        this.targetAccountNumber = targetAccountNumber;
    }
}
