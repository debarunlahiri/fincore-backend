package com.fincore.payment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fincore.AbstractIntegrationTest;
import com.fincore.common.exception.FincoreException;
import com.fincore.payment.bank.ExternalBankGateway;
import com.fincore.payment.entity.Payment;
import com.fincore.payment.entity.PaymentStatus;
import com.fincore.payment.entity.Rail;
import com.fincore.payment.job.PaymentRecoveryJob;
import com.fincore.payment.repository.PaymentRepository;
import com.fincore.payment.service.PaymentService;
import com.fincore.payment.service.PaymentSteps;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Saga against a real database and the simulated bank, with banking-core replaced by {@code
 * FakeLedger}.
 */
public class PaymentSagaIntegrationTest extends AbstractIntegrationTest {

    @Autowired PaymentService paymentService;
    @Autowired PaymentSteps steps;
    @Autowired PaymentRepository payments;
    @Autowired PaymentRecoveryJob recoveryJob;
    @Autowired ExternalBankGateway gateway;
    @Autowired JdbcTemplate jdbc;

    private final UUID userId = UUID.randomUUID();
    private final String accountNumber =
            "FC" + (1_000_000_000L + (long) (Math.random() * 1_000_000));
    private final UUID accountId =
            UUID.nameUUIDFromBytes(accountNumber.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private PaymentService.Outcome pay(String beneficiary, String amount, String key) {
        return paymentService.pay(
                userId, key, accountNumber, Rail.UPI, beneficiary, new BigDecimal(amount));
    }

    private PaymentService.Outcome pay(String beneficiary) {
        return pay(beneficiary, "100.00", UUID.randomUUID().toString());
    }

    private Payment reload(Payment p) {
        return payments.findById(p.getId()).orElseThrow();
    }

    private List<String> eventTypes(Payment p) {
        return jdbc.queryForList(
                "select event_type from outbox_events where aggregate_id = ? order by created_at",
                String.class,
                p.getId());
    }

    private static String code(Throwable t) {
        return ((FincoreException) t).code();
    }

    @Test
    void acceptedPaymentIsDebitedThenSettled() {
        var outcome = pay("shop@upi");

        Payment p = reload(outcome.payment());
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(p.getDebitJournalId()).isNotNull();
        assertThat(ledger.effectiveDebits()).isEqualTo(1);
        assertThat(ledger.effectiveReversals()).isZero();
        assertThat(eventTypes(p)).containsExactly("PaymentInitiated", "PaymentSettled");
    }

    @Test
    void declinedPaymentIsCompensatedWithExactlyOneReversal() {
        var outcome = pay("FAIL-bank-account");

        Payment p = reload(outcome.payment());
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(p.getFailureReason()).contains("Declined");
        assertThat(p.getReversalJournalId()).isNotNull();
        assertThat(ledger.effectiveDebits()).isEqualTo(1);
        assertThat(ledger.effectiveReversals()).isEqualTo(1);
        assertThat(eventTypes(p)).containsExactly("PaymentInitiated", "PaymentRefunded");
    }

    @Test
    void ledgerRejectionFailsThePaymentWithoutCallingTheBank() {
        ledger.rejectDebitsWith("INSUFFICIENT_FUNDS");

        var outcome = pay("shop@upi");

        Payment p = reload(outcome.payment());
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(gateway.status(p.getId().toString()))
                .isEqualTo(ExternalBankGateway.Status.UNKNOWN);
        assertThat(ledger.effectiveReversals()).isZero();
        assertThat(eventTypes(p)).containsExactly("PaymentFailed");
    }

    @Test
    void bankTimeoutLeavesTheMoneyDebitedUntilRecoveryResolvesIt() {
        var outcome = pay("TIMEOUT-bank-account");

        assertThat(reload(outcome.payment()).getStatus()).isEqualTo(PaymentStatus.DEBITED);
        assertThat(ledger.effectiveReversals()).isZero();

        int resolved = recoveryJob.recoverStuckPayments();

        assertThat(resolved).isGreaterThanOrEqualTo(1);
        assertThat(reload(outcome.payment()).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(ledger.effectiveReversals()).isEqualTo(1);
    }

    @Test
    void ledgerOutageAtTheStartLeavesTheIntentRecordedAndRecoveryCompletesIt() {
        ledger.setDown(true);

        var outcome = pay("shop@upi");

        assertThat(reload(outcome.payment()).getStatus()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(gateway.status(outcome.payment().getId().toString()))
                .isEqualTo(ExternalBankGateway.Status.UNKNOWN);

        ledger.setDown(false);
        recoveryJob.recoverStuckPayments();

        assertThat(reload(outcome.payment()).getStatus()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(ledger.effectiveDebits()).isEqualTo(1);
    }

    @Test
    void ledgerOutageDuringCompensationKeepsThePaymentDebitedAndRecoveryRefundsLater() {
        var outcome = pay("shop@upi");
        // simulate: debited, bank said no, but banking-core is unreachable when we try to reverse
        Payment p =
                steps.create(
                        userId,
                        "k-" + UUID.randomUUID(),
                        accountId,
                        accountNumber,
                        Rail.UPI,
                        "FAIL-x",
                        new BigDecimal("50"),
                        "h");
        ledger.debit(p.getId(), userId, accountId, new BigDecimal("50"), "x");
        steps.markDebited(p.getId(), UUID.randomUUID());
        ledger.setDown(true);

        paymentService.drive(reload(p), false);
        assertThat(reload(p).getStatus()).isEqualTo(PaymentStatus.DEBITED);

        ledger.setDown(false);
        recoveryJob.recoverStuckPayments();

        assertThat(reload(p).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(ledger.effectiveReversals()).isEqualTo(1);
        assertThat(reload(outcome.payment()).getStatus()).isEqualTo(PaymentStatus.SETTLED);
    }

    @Test
    void paymentDebitedButNeverSubmittedToTheBankIsSubmittedByRecovery() {
        // a crash between "markDebited" and the bank call leaves exactly this state
        Payment p =
                steps.create(
                        userId,
                        "k-" + UUID.randomUUID(),
                        accountId,
                        accountNumber,
                        Rail.IMPS,
                        "shop@upi",
                        new BigDecimal("75"),
                        "h");
        var posted = ledger.debit(p.getId(), userId, accountId, new BigDecimal("75"), "x");
        steps.markDebited(p.getId(), posted.journalId());
        assertThat(gateway.status(p.getId().toString()))
                .isEqualTo(ExternalBankGateway.Status.UNKNOWN);

        recoveryJob.recoverStuckPayments();

        assertThat(reload(p).getStatus()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(gateway.status(p.getId().toString()))
                .isEqualTo(ExternalBankGateway.Status.SUCCESS);
    }

    @Test
    void recoveryRunningRepeatedlyNeverReversesTwice() {
        pay("TIMEOUT-a");

        recoveryJob.recoverStuckPayments();
        recoveryJob.recoverStuckPayments();
        recoveryJob.recoverStuckPayments();

        assertThat(ledger.effectiveReversals()).isEqualTo(1);
    }

    @Test
    void stepsAreNoOpsOnAPaymentThatAlreadyFinished() {
        Payment settled = pay("shop@upi").payment();

        steps.markRefunded(settled.getId(), "late", UUID.randomUUID());
        steps.markFailed(settled.getId(), "late");
        steps.settle(settled.getId());

        assertThat(reload(settled).getStatus()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(ledger.effectiveReversals()).isZero();
    }

    @Test
    void retryWithSameKeyReplaysWithoutDebitingAgain() {
        String key = UUID.randomUUID().toString();

        var first = pay("shop@upi", "100.00", key);
        var retry = pay("shop@upi", "100.00", key);

        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.payment().getId()).isEqualTo(first.payment().getId());
        assertThat(ledger.debitCalls()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() {
        String key = UUID.randomUUID().toString();
        pay("shop@upi", "100.00", key);

        assertThatThrownBy(() -> pay("shop@upi", "200.00", key))
                .isInstanceOf(FincoreException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        assertThatThrownBy(() -> pay("shop@upi", "100.00", "  "))
                .isInstanceOf(FincoreException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void concurrentRetriesWithSameKeyCreateOnePaymentAndOneDebit() throws Exception {
        String key = UUID.randomUUID().toString();
        var pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                tasks.add(() -> pay("shop@upi", "100.00", key).replayed());
            }
            long created = 0;
            for (var future : pool.invokeAll(tasks)) {
                if (!future.get()) {
                    created++;
                }
            }
            assertThat(created).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(ledger.effectiveDebits()).isEqualTo(1);
    }

    @Test
    void railCeilingIsEnforcedBeforeAnythingIsRecorded() {
        long before = payments.count();

        assertThatThrownBy(() -> pay("shop@upi", "100000.01", UUID.randomUUID().toString()))
                .isInstanceOf(FincoreException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("RAIL_LIMIT_EXCEEDED"));

        assertThat(payments.count()).isEqualTo(before);
        assertThat(ledger.debitCalls()).isZero();
    }
}
