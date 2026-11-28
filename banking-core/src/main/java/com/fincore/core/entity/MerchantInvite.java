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

/**
 * A main merchant's offer to open a franchise outlet. The reference is the invite code that the
 * franchisee enters to join; the royalty is agreed up front.
 */
@Entity
@Table(name = "merchant_invites")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MerchantInvite {

    public static final String PENDING = "PENDING";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String reference = Reference.next("INV");

    @Column(name = "parent_merchant_id", nullable = false)
    private UUID parentMerchantId;

    @Column(name = "royalty_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal royaltyPercent;

    private String note;

    @Column(nullable = false)
    private String status = PENDING;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public MerchantInvite(
            UUID parentMerchantId, BigDecimal royaltyPercent, String note, Instant expiresAt) {
        this.parentMerchantId = parentMerchantId;
        this.royaltyPercent = royaltyPercent;
        this.note = note;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable() {
        return PENDING.equals(status) && expiresAt.isAfter(Instant.now());
    }

    public void accept() {
        status = ACCEPTED;
    }

    public void cancel() {
        status = CANCELLED;
    }
}
