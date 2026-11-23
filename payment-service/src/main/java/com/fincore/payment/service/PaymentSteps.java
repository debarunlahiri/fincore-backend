package com.fincore.payment.service;

import com.fincore.audit.service.AuditService;
import com.fincore.common.exception.FincoreException;
import com.fincore.outbox.service.OutboxService;
import com.fincore.payment.entity.Payment;
import com.fincore.payment.entity.PaymentStatus;
import com.fincore.payment.entity.Rail;
import com.fincore.payment.event.PaymentEvent;
import com.fincore.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The local, transactional steps of the payment Saga. Each method is one short database transaction. Remote
 * calls (ledger, bank) happen between them, outside any transaction and without holding locks. Every state
 * change re-reads the payment under a row lock and is a no-op if the payment already moved on, so the request
 * thread and the recovery job can never apply the same step twice.
 */
@Component
public class PaymentSteps {

    private static final String CURRENCY = "INR";

    private final PaymentRepository payments;
    private final OutboxService outbox;
    private final AuditService audit;

    public PaymentSteps(PaymentRepository payments, OutboxService outbox, AuditService audit) {
        this.payments = payments;
        this.outbox = outbox;
        this.audit = audit;
    }

    /** Step 0: durably record the intent before any money moves, so a crash can always be recovered. */
    @Transactional
    public Payment create(
            UUID userId, String key, UUID accountId, Rail rail, String beneficiaryRef, BigDecimal amount, String requestHash) {
        if (amount.compareTo(rail.maxAmount()) > 0) {
            throw FincoreException.unprocessable("RAIL_LIMIT_EXCEEDED", rail + " payments are limited to " + rail.maxAmount());
        }
        return payments.saveAndFlush(
                new Payment(userId, key, accountId, rail, beneficiaryRef, amount, CURRENCY, requestHash));
    }

    /** Step 1 done: the ledger debited the customer. */
    @Transactional
    public Payment markDebited(UUID paymentId, UUID journalId) {
        Payment payment = lock(paymentId);
        if (payment.getStatus() == PaymentStatus.REQUESTED) {
            payment.markDebited(journalId);
            outbox.record("PAYMENT", payment.getId(), "PaymentInitiated", PaymentEvent.of(payment));
        }
        return payment;
    }

    /** Step 1 rejected for good (insufficient funds, not the owner, ...): nothing was debited. */
    @Transactional
    public Payment markFailed(UUID paymentId, String reason) {
        Payment payment = lock(paymentId);
        if (payment.getStatus() == PaymentStatus.REQUESTED) {
            payment.markFailed(reason);
            outbox.record("PAYMENT", payment.getId(), "PaymentFailed", PaymentEvent.of(payment));
        }
        return payment;
    }

    /** Step 2a: the bank accepted. */
    @Transactional
    public Payment settle(UUID paymentId) {
        Payment payment = lock(paymentId);
        if (payment.getStatus() == PaymentStatus.DEBITED) {
            payment.markSettled();
            outbox.record("PAYMENT", payment.getId(), "PaymentSettled", PaymentEvent.of(payment));
        }
        return payment;
    }

    /** Step 2b (compensation done): the ledger reversed the debit. */
    @Transactional
    public Payment markRefunded(UUID paymentId, String reason, UUID reversalJournalId) {
        Payment payment = lock(paymentId);
        if (payment.getStatus() == PaymentStatus.DEBITED) {
            payment.markRefunded(reason, reversalJournalId);
            outbox.record("PAYMENT", payment.getId(), "PaymentRefunded", PaymentEvent.of(payment));
            audit.record("system", "PAYMENT_REFUNDED", "PAYMENT", payment.getId().toString(), reason);
        }
        return payment;
    }

    private Payment lock(UUID paymentId) {
        return payments.lockById(paymentId).orElseThrow(() -> FincoreException.notFound("Payment not found"));
    }
}
