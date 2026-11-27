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
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "loan_installments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanInstallment {

    public enum Status {
        PENDING,
        OVERDUE,
        PAID,
        WAIVED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "loan_id", nullable = false)
    private UUID loanId;

    @Column(nullable = false)
    private int number;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal emi;

    @Column(name = "principal_part", nullable = false, precision = 19, scale = 2)
    private BigDecimal principalPart;

    @Column(name = "interest_part", nullable = false, precision = 19, scale = 2)
    private BigDecimal interestPart;

    @Column(name = "late_fee", nullable = false, precision = 19, scale = 2)
    private BigDecimal lateFee = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "journal_id")
    private UUID journalId;

    public LoanInstallment(
            UUID loanId,
            int number,
            LocalDate dueDate,
            BigDecimal emi,
            BigDecimal principalPart,
            BigDecimal interestPart) {
        this.loanId = loanId;
        this.number = number;
        this.dueDate = dueDate;
        this.emi = emi;
        this.principalPart = principalPart;
        this.interestPart = interestPart;
    }

    public BigDecimal amountDue() {
        return emi.add(lateFee);
    }

    public void markOverdue(BigDecimal fee) {
        this.status = Status.OVERDUE;
        this.lateFee = fee;
    }

    public void pay(UUID journalId) {
        this.status = Status.PAID;
        this.paidAt = Instant.now();
        this.journalId = journalId;
    }

    public void waive() {
        this.status = Status.WAIVED;
    }
}
