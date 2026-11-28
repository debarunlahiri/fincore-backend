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

/**
 * A customer's merchant profile. Payments land in its own MERCHANT account. {@code reference} is
 * the merchant code customers type to pay it.
 */
@Entity
@Table(name = "merchants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String reference = Reference.next("MER");

    @Column(name = "owner_user_id", nullable = false, unique = true)
    private UUID ownerUserId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(nullable = false)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MerchantPlan plan = MerchantPlan.STARTER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MerchantStatus status = MerchantStatus.ACTIVE;

    /** Set on a franchise outlet (sub-merchant): the main merchant (franchisor) it belongs to. */
    @Column(name = "parent_merchant_id")
    private UUID parentMerchantId;

    /** Share of every payment's net amount passed to the main merchant at settlement. */
    @Column(name = "royalty_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal royaltyPercent = BigDecimal.ZERO;

    /** Royalty that could not be paid to the main merchant yet; paid with the next settlement. */
    @Column(name = "royalty_owed", nullable = false, precision = 19, scale = 2)
    private BigDecimal royaltyOwed = BigDecimal.ZERO;

    /** Consecutive failed settlement attempts; the daily job backs off until the retry time. */
    @Column(name = "settlement_failures", nullable = false)
    private int settlementFailures;

    @Column(name = "settlement_retry_at")
    private Instant settlementRetryAt;

    @Column(name = "settlement_error")
    private String settlementError;

    @Column(name = "plan_changed_at")
    private Instant planChangedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Merchant(
            UUID ownerUserId,
            UUID accountId,
            String accountNumber,
            String businessName,
            String category) {
        this.ownerUserId = ownerUserId;
        this.accountId = accountId;
        this.accountNumber = accountNumber;
        this.businessName = businessName;
        this.category = category;
    }

    public static Merchant outlet(
            Merchant parent,
            UUID ownerUserId,
            UUID accountId,
            String accountNumber,
            String businessName,
            BigDecimal royaltyPercent) {
        Merchant m =
                new Merchant(ownerUserId, accountId, accountNumber, businessName, parent.category);
        m.parentMerchantId = parent.id;
        m.royaltyPercent = royaltyPercent;
        m.plan = parent.plan;
        return m;
    }

    public void recordSettlementFailure(String reason, Instant retryAt) {
        settlementFailures++;
        settlementError = reason;
        settlementRetryAt = retryAt;
    }

    public void clearSettlementFailure() {
        settlementFailures = 0;
        settlementError = null;
        settlementRetryAt = null;
    }

    public void setRoyaltyOwed(BigDecimal amount) {
        royaltyOwed = amount;
    }

    public boolean isSubMerchant() {
        return parentMerchantId != null;
    }

    public void suspend() {
        status = MerchantStatus.SUSPENDED;
    }

    public void resume() {
        status = MerchantStatus.ACTIVE;
    }

    public boolean isActive() {
        return status == MerchantStatus.ACTIVE;
    }

    public void changePlan(MerchantPlan newPlan) {
        this.plan = newPlan;
        this.planChangedAt = Instant.now();
    }
}
