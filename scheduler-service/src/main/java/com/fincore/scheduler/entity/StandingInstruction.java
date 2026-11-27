package com.fincore.scheduler.entity;

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
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "standing_instructions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StandingInstruction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "source_account_number", nullable = false)
    private String sourceAccountNumber;

    /** Customer-facing number; the internal id never leaves the service. */
    @Column(nullable = false, updatable = false, unique = true)
    private String reference = com.fincore.common.util.Reference.next("SIN");

    @Column(name = "target_account_number", nullable = false)
    private String targetAccountNumber;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    private String remarks;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Frequency frequency;

    @Column(name = "next_run_date", nullable = false)
    private LocalDate nextRunDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InstructionStatus status = InstructionStatus.ACTIVE;

    @Column(name = "last_run_status")
    private String lastRunStatus;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public StandingInstruction(
            UUID userId,
            UUID sourceAccountId,
            String sourceAccountNumber,
            String targetAccountNumber,
            BigDecimal amount,
            String currency,
            String remarks,
            Frequency frequency,
            LocalDate firstRun) {
        this.userId = userId;
        this.sourceAccountId = sourceAccountId;
        this.sourceAccountNumber = sourceAccountNumber;
        this.targetAccountNumber = targetAccountNumber;
        this.amount = amount;
        this.currency = currency;
        this.remarks = remarks;
        this.frequency = frequency;
        this.nextRunDate = firstRun;
    }

    /**
     * The idempotency key for the run of {@code runDate}: re-running the same date can never pay
     * twice.
     */
    public String idempotencyKeyFor(LocalDate runDate) {
        return "SI-" + id + "-" + runDate;
    }

    public void recordRun(String outcome) {
        this.lastRunStatus = outcome;
        this.lastRunAt = Instant.now();
        this.nextRunDate = frequency.next(nextRunDate);
    }

    public void pause() {
        requireNotCancelled();
        this.status = InstructionStatus.PAUSED;
    }

    public void resume(LocalDate today) {
        requireNotCancelled();
        this.status = InstructionStatus.ACTIVE;
        // do not fire a backlog of runs that were missed while paused
        if (nextRunDate.isBefore(today)) {
            this.nextRunDate = today;
        }
    }

    public void cancel() {
        this.status = InstructionStatus.CANCELLED;
    }

    private void requireNotCancelled() {
        if (status == InstructionStatus.CANCELLED) {
            throw FincoreException.conflict(
                    "INSTRUCTION_CANCELLED", "A cancelled instruction cannot be changed");
        }
    }
}
