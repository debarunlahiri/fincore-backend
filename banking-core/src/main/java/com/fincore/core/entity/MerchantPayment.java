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

/** One customer payment to a merchant. The fee and royalty are fixed at payment time. */
@Entity
@Table(name = "merchant_payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MerchantPayment {

    public static final String SUCCESS = "SUCCESS";
    public static final String REFUNDED = "REFUNDED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String reference = Reference.next("MPY");

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    /** Null for a guest payment. */
    @Column(name = "payer_user_id")
    private UUID payerUserId;

    @Column(name = "payer_account_id")
    private UUID payerAccountId;

    /** Masked for the merchant, e.g. FC******1234. */
    @Column(name = "payer_account_masked", nullable = false)
    private String payerAccountMasked;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal fee;

    /** Share for the main merchant (franchisor); zero for a standalone merchant. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal royalty;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal netAmount;

    @Column(nullable = false)
    private String status = SUCCESS;

    /** Null until the payment is paid out in a settlement. */
    @Column(name = "settlement_id")
    private UUID settlementId;

    private String note;

    @Column(name = "journal_reference", nullable = false)
    private String journalReference;

    @Column(name = "refund_journal_reference")
    private String refundJournalReference;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public MerchantPayment(
            UUID merchantId,
            UUID payerUserId,
            UUID payerAccountId,
            String payerAccountMasked,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal royalty,
            String note,
            String journalReference) {
        this.merchantId = merchantId;
        this.payerUserId = payerUserId;
        this.payerAccountId = payerAccountId;
        this.payerAccountMasked = payerAccountMasked;
        this.amount = amount;
        this.fee = fee;
        this.royalty = royalty;
        this.netAmount = amount.subtract(fee).subtract(royalty);
        this.note = note;
        this.journalReference = journalReference;
    }

    /** A payment taken from a guest through the gateway: no customer, no account. */
    public static MerchantPayment guest(
            UUID merchantId,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal royalty,
            String note,
            String journalReference) {
        return new MerchantPayment(
                merchantId, null, null, "Guest (Razorpay)", amount, fee, royalty, note, journalReference);
    }

    public boolean isRefunded() {
        return REFUNDED.equals(status);
    }

    public boolean isSettled() {
        return settlementId != null;
    }

    public void markSettled(UUID settlementId) {
        this.settlementId = settlementId;
    }

    public void markRefunded(String refundJournalReference) {
        this.status = REFUNDED;
        this.refundJournalReference = refundJournalReference;
        this.refundedAt = Instant.now();
    }
}
