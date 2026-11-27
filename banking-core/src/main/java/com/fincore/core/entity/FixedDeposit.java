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
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "fixed_deposits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FixedDeposit {

    public enum Status {
        ACTIVE,
        MATURED,
        CLOSED_EARLY
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String reference = Reference.next("FDR");

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal principal;

    @Column(name = "annual_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal annualRate;

    @Column(name = "tenure_months", nullable = false)
    private int tenureMonths;

    @Column(name = "opened_on", nullable = false)
    private LocalDate openedOn;

    @Column(name = "maturity_date", nullable = false)
    private LocalDate maturityDate;

    @Column(name = "maturity_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal maturityAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(name = "payout_amount", precision = 19, scale = 2)
    private BigDecimal payoutAmount;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "open_journal_id", nullable = false)
    private UUID openJournalId;

    @Column(name = "close_journal_id")
    private UUID closeJournalId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public FixedDeposit(
            UUID ownerUserId,
            UUID accountId,
            String accountNumber,
            BigDecimal principal,
            BigDecimal annualRate,
            int tenureMonths,
            LocalDate openedOn,
            BigDecimal maturityAmount,
            UUID openJournalId) {
        this.ownerUserId = ownerUserId;
        this.accountId = accountId;
        this.accountNumber = accountNumber;
        this.principal = principal;
        this.annualRate = annualRate;
        this.tenureMonths = tenureMonths;
        this.openedOn = openedOn;
        this.maturityDate = openedOn.plusMonths(tenureMonths);
        this.maturityAmount = maturityAmount;
        this.openJournalId = openJournalId;
    }

    public void close(Status newStatus, BigDecimal payout, UUID journalId) {
        this.status = newStatus;
        this.payoutAmount = payout;
        this.closeJournalId = journalId;
        this.closedAt = Instant.now();
    }
}
