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
 * One payout of a merchant's eligible payments: gross taken, minus the platform fee, minus the
 * royalty passed to the main merchant, equals what lands in the merchant account.
 */
@Entity
@Table(name = "merchant_settlements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MerchantSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String reference = Reference.next("STL");

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    /** The main merchant that received the royalty, if any. */
    @Column(name = "royalty_merchant_id")
    private UUID royaltyMerchantId;

    @Column(name = "payment_count", nullable = false)
    private int paymentCount;

    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal grossAmount;

    @Column(name = "fee_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal feeAmount;

    @Column(name = "royalty_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal royaltyAmount;

    /** Royalty that stayed on hold in this settlement because the main merchant could not receive it. */
    @Column(name = "royalty_held", nullable = false, precision = 19, scale = 2)
    private BigDecimal royaltyHeld;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal netAmount;

    /** MANUAL (merchant pressed settle) or SCHEDULED (daily job). */
    @Column(name = "triggered_by", nullable = false)
    private String triggeredBy;

    @Column(name = "journal_reference", nullable = false)
    private String journalReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public MerchantSettlement(
            UUID merchantId,
            UUID royaltyMerchantId,
            int paymentCount,
            BigDecimal grossAmount,
            BigDecimal feeAmount,
            BigDecimal royaltyAmount,
            BigDecimal royaltyHeld,
            BigDecimal netAmount,
            String triggeredBy,
            String journalReference) {
        this.merchantId = merchantId;
        this.royaltyMerchantId = royaltyMerchantId;
        this.paymentCount = paymentCount;
        this.grossAmount = grossAmount;
        this.feeAmount = feeAmount;
        this.royaltyAmount = royaltyAmount;
        this.royaltyHeld = royaltyHeld;
        this.netAmount = netAmount;
        this.triggeredBy = triggeredBy;
        this.journalReference = journalReference;
    }
}
