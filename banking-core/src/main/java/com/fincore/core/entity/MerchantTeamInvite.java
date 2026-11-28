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

/** The owner's offer of a team role; the invite code is what the new member enters to join. */
@Entity
@Table(name = "merchant_team_invites")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MerchantTeamInvite {

    public static final String PENDING = "PENDING";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String reference = Reference.next("TIM");

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MerchantRole role;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private String status = PENDING;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public MerchantTeamInvite(UUID merchantId, MerchantRole role, String label, Instant expiresAt) {
        this.merchantId = merchantId;
        this.role = role;
        this.label = label;
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
