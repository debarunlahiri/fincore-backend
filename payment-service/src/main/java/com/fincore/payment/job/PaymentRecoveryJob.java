package com.fincore.payment.job;

import com.fincore.payment.entity.Payment;
import com.fincore.payment.repository.PaymentRepository;
import com.fincore.payment.service.PaymentService;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finishes Sagas that were interrupted (bank timeout, banking-core outage, app crash between steps): every
 * payment still REQUESTED or DEBITED after a grace period is driven forward again. Safe on several instances:
 * every step takes a row lock and is a no-op on a payment that already moved on, and the ledger operations are
 * idempotent on the payment id.
 */
@Component
public class PaymentRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryJob.class);

    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final Duration minAge;

    public PaymentRecoveryJob(
            PaymentRepository payments,
            PaymentService paymentService,
            @Value("${fincore.payments.recovery-min-age-seconds:60}") long minAgeSeconds) {
        this.payments = payments;
        this.paymentService = paymentService;
        this.minAge = Duration.ofSeconds(minAgeSeconds);
    }

    @Scheduled(fixedDelayString = "${fincore.payments.recovery-interval-ms:30000}")
    public void run() {
        int resolved = recoverStuckPayments();
        if (resolved > 0) {
            log.info("Payment recovery resolved {} payment(s)", resolved);
        }
    }

    /** @return number of payments that reached a terminal state during this pass */
    public int recoverStuckPayments() {
        int resolved = 0;
        for (Payment stuck : payments.findStuck(Instant.now().minus(minAge), PageRequest.of(0, 50))) {
            try {
                if (paymentService.drive(stuck, true).getStatus().isTerminal()) {
                    resolved++;
                }
            } catch (RuntimeException e) {
                log.warn("Recovery of payment {} failed, will retry: {}", stuck.getId(), e.toString());
            }
        }
        return resolved;
    }
}
