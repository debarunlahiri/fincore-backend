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
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** One business event that groups two or more balanced ledger entries. Append-only. */
@Entity
@Immutable
@Table(name = "journals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Journal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JournalType type;

    private String description;

    /** Customer-facing number shown on receipts and statements (never the internal id). */
    @Column(nullable = false, updatable = false, unique = true)
    private String reference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    private static String prefix(JournalType type) {
        return switch (type) {
            case TRANSFER -> "TXN";
            case DEPOSIT -> "DEP";
            case WITHDRAWAL -> "WDL";
            case PAYMENT -> "PMT";
            case PAYMENT_REVERSAL -> "RVL";
            case FD_OPEN, FD_CLOSE -> "FDT";
            case LOAN_DISBURSAL, LOAN_REPAYMENT -> "LNT";
            case CARD_PURCHASE -> "CRD";
        };
    }

    public Journal(JournalType type, String description) {
        this.type = type;
        this.description = description;
        this.reference = Reference.next(prefix(type));
    }
}
