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

@Entity
@Table(name = "beneficiaries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Beneficiary {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Customer-facing identifier of the payee (used to remove it); the internal id never leaves the
     * service.
     */
    @Column(nullable = false, unique = true, updatable = false)
    private String reference = Reference.next("BEN");

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BeneficiaryType type;

    @Column(nullable = false)
    private String name;

    private String nickname;

    /**
     * Normalised unique key per owner: account number (FINCORE), "IFSC:account" (BANK) or the
     * lower-case UPI id.
     */
    @Column(nullable = false)
    private String identifier;

    @Column(name = "account_number")
    private String accountNumber;

    private String ifsc;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "upi_id")
    private String upiId;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "active_from", nullable = false)
    private Instant activeFrom;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Beneficiary(
            UUID ownerUserId,
            BeneficiaryType type,
            String name,
            String nickname,
            String identifier,
            String accountNumber,
            String ifsc,
            String bankName,
            String upiId,
            Instant activeFrom) {
        this.ownerUserId = ownerUserId;
        this.type = type;
        this.name = name;
        this.nickname = nickname;
        this.identifier = identifier;
        this.accountNumber = accountNumber;
        this.ifsc = ifsc;
        this.bankName = bankName;
        this.upiId = upiId;
        this.activeFrom = activeFrom;
    }

    /**
     * New payees are PENDING until the cooling period ends; only then can money be sent to them.
     */
    public boolean isUsable() {
        return !Instant.now().isBefore(activeFrom);
    }
}
