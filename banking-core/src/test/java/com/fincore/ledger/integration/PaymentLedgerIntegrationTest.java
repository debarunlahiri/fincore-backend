package com.fincore.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fincore.AbstractIntegrationTest;
import com.fincore.common.exception.FincoreException;
import com.fincore.ledger.service.PaymentLedgerService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The idempotent ledger operations the payment-service Saga relies on. */
public class PaymentLedgerIntegrationTest extends AbstractIntegrationTest {

    @Autowired PaymentLedgerService paymentLedger;

    private PaymentLedgerService.Posted debit(TestCustomer c, UUID paymentId, String amount) {
        return paymentLedger.debit(paymentId, c.user().userId(), c.account().getId(), new BigDecimal(amount), "test payment");
    }

    private static String code(Throwable t) {
        return ((FincoreException) t).code();
    }

    @Test
    void debitMovesMoneyToTheSettlementAccount() {
        TestCustomer c = newCustomer(new BigDecimal("1000"));

        var posted = debit(c, UUID.randomUUID(), "250.00");

        assertThat(posted.replayed()).isFalse();
        assertThat(posted.journalId()).isNotNull();
        assertThat(balanceOf(c.account())).isEqualByComparingTo("750");
        assertThat(reconciliation.reconcile().consistent()).isTrue();
    }

    @Test
    void repeatingTheSameDebitIsAReplayAndNeverDebitsTwice() {
        TestCustomer c = newCustomer(new BigDecimal("1000"));
        UUID paymentId = UUID.randomUUID();

        var first = debit(c, paymentId, "250.00");
        var retry = debit(c, paymentId, "250.00");

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.journalId()).isEqualTo(first.journalId());
        assertThat(balanceOf(c.account())).isEqualByComparingTo("750");
    }

    @Test
    void concurrentIdenticalDebitsApplyExactlyOnce() throws Exception {
        TestCustomer c = newCustomer(new BigDecimal("1000"));
        UUID paymentId = UUID.randomUUID();

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> debit(c, paymentId, "100.00").replayed());
        }
        List<Boolean> replayed = runConcurrently(tasks);

        assertThat(replayed.stream().filter(r -> !r).count()).isEqualTo(1);
        assertThat(balanceOf(c.account())).isEqualByComparingTo("900");
    }

    @Test
    void debitOfSomeoneElsesAccountIsRejectedAndChangesNothing() {
        TestCustomer owner = newCustomer(new BigDecimal("1000"));
        TestCustomer attacker = newCustomer(BigDecimal.ZERO);

        assertThatThrownBy(() -> paymentLedger.debit(
                        UUID.randomUUID(), attacker.user().userId(), owner.account().getId(), BigDecimal.TEN, "x"))
                .isInstanceOf(FincoreException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("NOT_FOUND"));
        assertThat(balanceOf(owner.account())).isEqualByComparingTo("1000");
    }

    @Test
    void debitWithInsufficientFundsFailsWithoutLeavingAReference() {
        TestCustomer c = newCustomer(new BigDecimal("50"));
        UUID paymentId = UUID.randomUUID();

        assertThatThrownBy(() -> debit(c, paymentId, "100.00"))
                .isInstanceOf(FincoreException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("INSUFFICIENT_FUNDS"));

        // the failed attempt rolled back completely, so after a top-up the same payment id can succeed
        cash.deposit(c.user().userId(), c.account().getId(), new BigDecimal("100"));
        assertThat(debit(c, paymentId, "100.00").replayed()).isFalse();
    }

    @Test
    void reversalRestoresTheBalanceUsingTheStoredOriginalAmount() {
        TestCustomer c = newCustomer(new BigDecimal("1000"));
        UUID paymentId = UUID.randomUUID();
        debit(c, paymentId, "300.00");

        var reversal = paymentLedger.reverse(paymentId);

        assertThat(reversal.replayed()).isFalse();
        assertThat(balanceOf(c.account())).isEqualByComparingTo("1000");
        assertThat(reconciliation.reconcile().consistent()).isTrue();
    }

    @Test
    void repeatingTheReversalIsAReplayAndNeverRefundsTwice() {
        TestCustomer c = newCustomer(new BigDecimal("1000"));
        UUID paymentId = UUID.randomUUID();
        debit(c, paymentId, "300.00");

        var first = paymentLedger.reverse(paymentId);
        var retry = paymentLedger.reverse(paymentId);

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.journalId()).isEqualTo(first.journalId());
        assertThat(balanceOf(c.account())).isEqualByComparingTo("1000");
    }

    @Test
    void concurrentReversalsRefundExactlyOnce() throws Exception {
        TestCustomer c = newCustomer(new BigDecimal("1000"));
        UUID paymentId = UUID.randomUUID();
        debit(c, paymentId, "300.00");

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> paymentLedger.reverse(paymentId).replayed());
        }
        List<Boolean> replayed = runConcurrently(tasks);

        assertThat(replayed.stream().filter(r -> !r).count()).isEqualTo(1);
        assertThat(balanceOf(c.account())).isEqualByComparingTo("1000");
    }

    @Test
    void reversalWithoutAnOriginalDebitIsRejected() {
        assertThatThrownBy(() -> paymentLedger.reverse(UUID.randomUUID()))
                .isInstanceOf(FincoreException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("NOT_FOUND"));
    }
}
