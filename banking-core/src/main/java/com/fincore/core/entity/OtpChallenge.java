package com.fincore.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A one-time code bound to one user, one purpose and one specific action (the binding). Only a hash
 * of the code is stored.
 */
@Entity
@Table(name = "otp_challenges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OtpChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String reference;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String purpose;

    @Column(nullable = false)
    private String binding;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private boolean consumed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public OtpChallenge(
            String reference,
            UUID userId,
            String purpose,
            String binding,
            String codeHash,
            Instant expiresAt) {
        this.reference = reference;
        this.userId = userId;
        this.purpose = purpose;
        this.binding = binding;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public void recordFailure() {
        attempts++;
    }

    public void consume() {
        consumed = true;
    }
}
