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

/** A non-owner member of a merchant's team. A customer belongs to at most one merchant. */
@Entity
@Table(name = "merchant_members")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MerchantMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String reference = Reference.next("MEM");

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MerchantRole role;

    /** A name the owner gave when inviting, e.g. "Ravi, counter 1". */
    @Column(nullable = false)
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public MerchantMember(UUID merchantId, UUID userId, MerchantRole role, String label) {
        this.merchantId = merchantId;
        this.userId = userId;
        this.role = role;
        this.label = label;
    }

    public void changeRole(MerchantRole role) {
        this.role = role;
    }
}
