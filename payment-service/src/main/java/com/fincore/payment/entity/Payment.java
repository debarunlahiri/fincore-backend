package com.fincore.payment.entity;

import com.fincore.common.exception.FincoreException;
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
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "initiated_by", nullable = false)
    private UUID initiatedBy;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    /** Customer-facing payment number; the internal id never leaves the service. */
    @Column(nullable = false, updatable = false, unique = true)
    private String reference = com.fincore.common.util.Reference.next("PAY");

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Rail rail;

    @Column(name = "beneficiary_ref", nullable = false)
    private String beneficiaryRef;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status = PaymentStatus.REQUESTED;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "debit_journal_id")
    private UUID debitJournalId;

    @Column(name = "reversal_journal_id")
    private UUID reversalJournalId;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Payment(
            UUID initiatedBy,
            String idempotencyKey,
            UUID accountId,
            String accountNumber,
            Rail rail,
            String beneficiaryRef,
            BigDecimal amount,
            String currency,
            String requestHash) {
        this.initiatedBy = initiatedBy;
        this.idempotencyKey = idempotencyKey;
        this.accountId = accountId;
        this.accountNumber = accountNumber;
        this.rail = rail;
        this.beneficiaryRef = beneficiaryRef;
        this.amount = amount;
        this.currency = currency;
        this.requestHash = requestHash;
    }

    public void markDebited(UUID journalId) {
        transitionTo(PaymentStatus.DEBITED);
        this.debitJournalId = journalId;
    }

    public void markFailed(String reason) {
        transitionTo(PaymentStatus.FAILED);
        this.failureReason = reason;
    }

    public void markSettled() {
        transitionTo(PaymentStatus.SETTLED);
    }

    public void markRefunded(String reason, UUID reversalJournalId) {
        transitionTo(PaymentStatus.REFUNDED);
        this.failureReason = reason;
        this.reversalJournalId = reversalJournalId;
    }

    private void transitionTo(PaymentStatus next) {
        if (!status.canTransitionTo(next)) {
            throw FincoreException.conflict(
                    "INVALID_PAYMENT_STATE", "Cannot move payment from " + status + " to " + next);
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }
}
