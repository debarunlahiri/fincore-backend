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

/** A payment to a merchant by someone who is not signed in, taken through Razorpay. */
@Entity
@Table(name = "guest_payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GuestPayment {

    public static final String CREATED = "CREATED";
    public static final String PAID = "PAID";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String reference = Reference.next("GPY");

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    private String note;

    @Column(name = "payer_name", nullable = false)
    private String payerName;

    @Column(name = "payer_email", nullable = false)
    private String payerEmail;

    @Column(nullable = false)
    private String status = CREATED;

    @Column(name = "razorpay_order_id", nullable = false, unique = true)
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id")
    private String razorpayPaymentId;

    @Column(name = "merchant_payment_id")
    private UUID merchantPaymentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "paid_at")
    private Instant paidAt;

    public GuestPayment(
            UUID merchantId,
            BigDecimal amount,
            String note,
            String payerName,
            String payerEmail,
            String razorpayOrderId) {
        this.merchantId = merchantId;
        this.amount = amount;
        this.note = note;
        this.payerName = payerName;
        this.payerEmail = payerEmail;
        this.razorpayOrderId = razorpayOrderId;
    }

    public boolean isPaid() {
        return PAID.equals(status);
    }

    public void markPaid(String razorpayPaymentId, UUID merchantPaymentId) {
        this.status = PAID;
        this.razorpayPaymentId = razorpayPaymentId;
        this.merchantPaymentId = merchantPaymentId;
        this.paidAt = Instant.now();
    }
}
