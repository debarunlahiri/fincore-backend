package com.fincore.payment.service;

import com.fincore.common.exception.DependencyUnavailableException;
import com.fincore.common.exception.FincoreException;
import com.fincore.payment.bank.ExternalBankGateway;
import com.fincore.payment.client.LedgerPort;
import com.fincore.payment.entity.Payment;
import com.fincore.payment.entity.PaymentStatus;
import com.fincore.payment.entity.Rail;
import com.fincore.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestration-style distributed Saga across two systems that cannot share a transaction: the banking-core
 * ledger and the external bank. This class is deliberately not transactional, so no database locks are held
 * while waiting on the network, and {@link #drive} can be re-run from any persisted state by the recovery job.
 */
@Service
public class PaymentService {

    public record Outcome(Payment payment, boolean replayed) {}

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentSteps steps;
    private final PaymentRepository payments;
    private final LedgerPort ledger;
    private final ExternalBankGateway gateway;

    public PaymentService(PaymentSteps steps, PaymentRepository payments, LedgerPort ledger, ExternalBankGateway gateway) {
        this.steps = steps;
        this.payments = payments;
        this.ledger = ledger;
        this.gateway = gateway;
    }

    public Outcome pay(
            UUID userId, String idempotencyKey, UUID accountId, Rail rail, String beneficiaryRef, BigDecimal amount) {
        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (key.isEmpty() || key.length() > 100) {
            throw FincoreException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header (1-100 chars) is required");
        }
        String hash = fingerprint(accountId, rail, beneficiaryRef, amount);

        var existing = payments.findByInitiatedByAndIdempotencyKey(userId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }

        Payment payment;
        try {
            payment = steps.create(userId, key, accountId, rail, beneficiaryRef.trim(), amount, hash);
        } catch (DataIntegrityViolationException race) {
            return payments.findByInitiatedByAndIdempotencyKey(userId, key)
                    .map(p -> replay(p, hash))
                    .orElseThrow(() -> race);
        }
        return new Outcome(drive(payment, false), false);
    }

    /**
     * Pushes the payment as far forward as the world allows and returns where it ended up. It never throws for
     * downstream outages: an unreachable ledger or bank just leaves the payment in its current (persisted) state
     * for the recovery job. {@code recovery} makes it ask the bank for the real status before submitting again.
     */
    public Payment drive(Payment payment, boolean recovery) {
        Payment current = payment;
        if (current.getStatus() == PaymentStatus.REQUESTED) {
            current = debit(current);
        }
        if (current.getStatus() == PaymentStatus.DEBITED) {
            current = settleWithBank(current, recovery);
        }
        return current;
    }

    private Payment debit(Payment payment) {
        try {
            var posted = ledger.debit(
                    payment.getId(),
                    payment.getInitiatedBy(),
                    payment.getAccountId(),
                    payment.getAmount(),
                    payment.getRail() + " payment to " + payment.getBeneficiaryRef());
            return steps.markDebited(payment.getId(), posted.journalId());
        } catch (DependencyUnavailableException e) {
            // unknown whether banking-core applied it; the debit is idempotent on the payment id, so retry later
            log.warn("Ledger unavailable while debiting payment {}: {}", payment.getId(), e.getMessage());
            return payment;
        } catch (FincoreException e) {
            // definitive business rejection: no money moved, record why
            return steps.markFailed(payment.getId(), e.code());
        }
    }

    private Payment settleWithBank(Payment payment, boolean recovery) {
        String reference = payment.getId().toString();
        if (recovery) {
            switch (gateway.status(reference)) {
                case SUCCESS -> {
                    return steps.settle(payment.getId());
                }
                case FAILED -> {
                    return refund(payment, "Bank reported failure during recovery");
                }
                case UNKNOWN -> {
                    // the bank has never seen it (we crashed before submitting): safe to submit now
                }
            }
        }
        try {
            var result = gateway.submit(payment.getRail(), reference, payment.getBeneficiaryRef(), payment.getAmount());
            return result.accepted() ? steps.settle(payment.getId()) : refund(payment, result.reason());
        } catch (ExternalBankGateway.GatewayTimeoutException e) {
            // Unknown outcome: refunding now could double-pay if the bank did process it. Stay DEBITED; the
            // recovery job asks the bank for the real status and finishes the Saga.
            log.warn("Payment {} outcome unknown: {}", payment.getId(), e.getMessage());
            return payment;
        }
    }

    /** Compensation: reverse the ledger debit (idempotent), then record the refund. */
    private Payment refund(Payment payment, String reason) {
        try {
            var reversal = ledger.reverse(payment.getId());
            return steps.markRefunded(payment.getId(), reason, reversal.journalId());
        } catch (DependencyUnavailableException e) {
            log.warn("Ledger unavailable while refunding payment {}: {}", payment.getId(), e.getMessage());
            return payment;
        }
    }

    private static Outcome replay(Payment existing, String hash) {
        if (!existing.getRequestHash().equals(hash)) {
            throw FincoreException.unprocessable(
                    "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was already used with a different request");
        }
        return new Outcome(existing, true);
    }

    private static String fingerprint(UUID accountId, Rail rail, String beneficiaryRef, BigDecimal amount) {
        String canonical = String.join("|", accountId.toString(), rail.name(), beneficiaryRef.trim(),
                amount.stripTrailingZeros().toPlainString());
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
