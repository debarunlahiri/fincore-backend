package com.fincore.core.entity;

import com.fincore.common.util.Reference;
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
@Table(name = "loans")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Loan {

    /**
     * APPROVED = sanctioned, waiting for the customer to accept; ACTIVE = disbursed and being
     * repaid.
     */
    public enum Status {
        APPROVED,
        REJECTED,
        DECLINED,
        ACTIVE,
        CLOSED,
        FORECLOSED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String reference = Reference.next("LON");

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LoanType type;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal principal;

    @Column(name = "annual_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal annualRate;

    @Column(name = "tenure_months", nullable = false)
    private int tenureMonths;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal emi;

    @Column(name = "monthly_income", nullable = false, precision = 19, scale = 2)
    private BigDecimal monthlyIncome;

    @Column(name = "processing_fee", nullable = false, precision = 19, scale = 2)
    private BigDecimal processingFee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "outstanding_principal", nullable = false, precision = 19, scale = 2)
    private BigDecimal outstandingPrincipal = BigDecimal.ZERO;

    @Column(name = "disbursed_at")
    private Instant disbursedAt;

    @Column(name = "disbursal_journal_id")
    private UUID disbursalJournalId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Loan(
            UUID ownerUserId,
            UUID accountId,
            String accountNumber,
            LoanType type,
            BigDecimal principal,
            BigDecimal annualRate,
            int tenureMonths,
            BigDecimal emi,
            BigDecimal monthlyIncome,
            BigDecimal processingFee,
            Status status,
            String rejectionReason) {
        this.ownerUserId = ownerUserId;
        this.accountId = accountId;
        this.accountNumber = accountNumber;
        this.type = type;
        this.principal = principal;
        this.annualRate = annualRate;
        this.tenureMonths = tenureMonths;
        this.emi = emi;
        this.monthlyIncome = monthlyIncome;
        this.processingFee = processingFee;
        this.status = status;
        this.rejectionReason = rejectionReason;
    }

    public void disburse(UUID journalId) {
        this.status = Status.ACTIVE;
        this.outstandingPrincipal = principal;
        this.disbursedAt = Instant.now();
        this.disbursalJournalId = journalId;
    }

    public void decline() {
        this.status = Status.DECLINED;
    }

    public void repaid(BigDecimal principalPart, boolean last) {
        this.outstandingPrincipal =
                outstandingPrincipal.subtract(principalPart).max(BigDecimal.ZERO);
        if (last) {
            this.status = Status.CLOSED;
            this.outstandingPrincipal = BigDecimal.ZERO;
        }
    }

    public void foreclose() {
        this.status = Status.FORECLOSED;
        this.outstandingPrincipal = BigDecimal.ZERO;
    }
}
