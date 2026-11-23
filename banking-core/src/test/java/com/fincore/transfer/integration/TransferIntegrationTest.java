package com.fincore.transfer.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fincore.AbstractIntegrationTest;
import com.fincore.common.exception.FincoreException;
import com.fincore.outbox.entity.OutboxStatus;
import com.fincore.outbox.relay.OutboxRelay;
import com.fincore.outbox.repository.OutboxRepository;
import com.fincore.transfer.dto.TransferRequest;
import com.fincore.transfer.dto.TransferResult;
import com.fincore.transfer.service.TransferService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class TransferIntegrationTest extends AbstractIntegrationTest {

    @Autowired TransferService transferService;
    @Autowired OutboxRelay outboxRelay;
    @Autowired OutboxRepository outboxRepository;

    private TransferResult send(TestCustomer from, TestCustomer to, String amount, String key) {
        var request = new TransferRequest(
                from.account().getId(), to.account().getAccountNumber(), new BigDecimal(amount), "INR", null);
        return transferService.transfer(from.user(), key, request);
    }

    private static String errorCode(Throwable t) {
        return ((FincoreException) t).code();
    }

    @Test
    void transferMovesMoneyAndKeepsBooksBalanced() {
        TestCustomer a = newCustomer(new BigDecimal("20000"));
        TestCustomer b = newCustomer(new BigDecimal("10000"));

        TransferResult result = send(a, b, "5000.00", UUID.randomUUID().toString());

        assertThat(result.replayed()).isFalse();
        assertThat(balanceOf(a.account())).isEqualByComparingTo("15000");
        assertThat(balanceOf(b.account())).isEqualByComparingTo("15000");
        assertThat(reconciliation.reconcile().consistent()).isTrue();
    }

    @Test
    void retryWithSameKeyReturnsOriginalResultAndDoesNotDebitTwice() {
        TestCustomer a = newCustomer(new BigDecimal("1000"));
        TestCustomer b = newCustomer(BigDecimal.ZERO);
        String key = UUID.randomUUID().toString();

        TransferResult first = send(a, b, "100", key);
        TransferResult retry = send(a, b, "100", key);

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.transfer().id()).isEqualTo(first.transfer().id());
        assertThat(balanceOf(a.account())).isEqualByComparingTo("900");
    }

    @Test
    void reusingKeyWithDifferentPayloadIsRejected() {
        TestCustomer a = newCustomer(new BigDecimal("1000"));
        TestCustomer b = newCustomer(BigDecimal.ZERO);
        String key = UUID.randomUUID().toString();
        send(a, b, "100", key);

        assertThatThrownBy(() -> send(a, b, "200", key))
                .isInstanceOf(FincoreException.class)
                .satisfies(e -> assertThat(errorCode(e)).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void sameKeyUsedByDifferentCustomersIsIndependent() {
        TestCustomer a = newCustomer(new BigDecimal("1000"));
        TestCustomer b = newCustomer(new BigDecimal("1000"));
        TestCustomer sink = newCustomer(BigDecimal.ZERO);

        assertThat(send(a, sink, "100", "shared-key").replayed()).isFalse();
        assertThat(send(b, sink, "100", "shared-key").replayed()).isFalse();
        assertThat(balanceOf(sink.account())).isEqualByComparingTo("200");
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        TestCustomer a = newCustomer(new BigDecimal("1000"));
        TestCustomer b = newCustomer(BigDecimal.ZERO);

        assertThatThrownBy(() -> send(a, b, "100", "  "))
                .isInstanceOf(FincoreException.class)
                .satisfies(e -> assertThat(errorCode(e)).isEqualTo("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void insufficientFundsRollsBackEverything() {
        TestCustomer a = newCustomer(new BigDecimal("50"));
        TestCustomer b = newCustomer(BigDecimal.ZERO);

        assertThatThrownBy(() -> send(a, b, "100", UUID.randomUUID().toString()))
                .isInstanceOf(FincoreException.class)
                .satisfies(e -> assertThat(errorCode(e)).isEqualTo("INSUFFICIENT_FUNDS"));

        assertThat(balanceOf(a.account())).isEqualByComparingTo("50");
        assertThat(balanceOf(b.account())).isEqualByComparingTo("0");
    }

    @Test
    void failedTransferDoesNotLeaveAnIdempotencyRecord() {
        TestCustomer a = newCustomer(new BigDecimal("50"));
        TestCustomer b = newCustomer(BigDecimal.ZERO);
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> send(a, b, "100", key)).isInstanceOf(FincoreException.class);
        cash.deposit(a.user().userId(), a.account().getId(), new BigDecimal("100"));

        // the same key can be retried after the customer tops up, because the failed attempt rolled back fully
        assertThat(send(a, b, "100", key).replayed()).isFalse();
    }

    @Test
    void cannotTransferFromAnotherCustomersAccount() {
        TestCustomer a = newCustomer(new BigDecimal("1000"));
        TestCustomer b = newCustomer(BigDecimal.ZERO);
        TestCustomer attacker = newCustomer(BigDecimal.ZERO);

        var request = new TransferRequest(a.account().getId(), b.account().getAccountNumber(), BigDecimal.TEN, "INR", null);

        assertThatThrownBy(() -> transferService.transfer(attacker.user(), UUID.randomUUID().toString(), request))
                .isInstanceOf(FincoreException.class)
                .satisfies(e -> assertThat(errorCode(e)).isEqualTo("NOT_FOUND"));
        assertThat(balanceOf(a.account())).isEqualByComparingTo("1000");
    }

    @Test
    void transferToSameAccountIsRejected() {
        TestCustomer a = newCustomer(new BigDecimal("1000"));

        assertThatThrownBy(() -> send(a, a, "10", UUID.randomUUID().toString()))
                .isInstanceOf(FincoreException.class)
                .satisfies(e -> assertThat(errorCode(e)).isEqualTo("SAME_ACCOUNT"));
    }

    @Test
    void transferToUnknownAccountNumberIsRejected() {
        TestCustomer a = newCustomer(new BigDecimal("1000"));
        var request = new TransferRequest(a.account().getId(), "FC0000000000", BigDecimal.TEN, "INR", null);

        assertThatThrownBy(() -> transferService.transfer(a.user(), UUID.randomUUID().toString(), request))
                .isInstanceOf(FincoreException.class)
                .satisfies(e -> assertThat(errorCode(e)).isEqualTo("NOT_FOUND"));
    }

    @Test
    void unsupportedCurrencyIsRejected() {
        TestCustomer a = newCustomer(new BigDecimal("1000"));
        TestCustomer b = newCustomer(BigDecimal.ZERO);
        var request = new TransferRequest(a.account().getId(), b.account().getAccountNumber(), BigDecimal.TEN, "USD", null);

        assertThatThrownBy(() -> transferService.transfer(a.user(), UUID.randomUUID().toString(), request))
                .isInstanceOf(FincoreException.class)
                .satisfies(e -> assertThat(errorCode(e)).isEqualTo("CURRENCY_MISMATCH"));
    }

    @Test
    void dailyLimitCannotBeExceeded() {
        TestCustomer a = newCustomer(new BigDecimal("150000"));
        TestCustomer b = newCustomer(BigDecimal.ZERO);

        // default daily limit is 100,000.00; amounts below the 200,000 high-value fraud threshold
        send(a, b, "60000", UUID.randomUUID().toString());

        assertThatThrownBy(() -> send(a, b, "50000", UUID.randomUUID().toString()))
                .isInstanceOf(FincoreException.class)
                .satisfies(e -> assertThat(errorCode(e)).isEqualTo("DAILY_LIMIT_EXCEEDED"));
        assertThat(balanceOf(a.account())).isEqualByComparingTo("90000");
    }

    @Test
    void highValueTransferIsRejectedByFraudRules() {
        TestCustomer a = newCustomer(new BigDecimal("300000"));
        TestCustomer b = newCustomer(BigDecimal.ZERO);

        assertThatThrownBy(() -> send(a, b, "250000", UUID.randomUUID().toString()))
                .isInstanceOf(FincoreException.class)
                .satisfies(e -> assertThat(errorCode(e)).isEqualTo("FRAUD_REJECTED"));
        assertThat(balanceOf(a.account())).isEqualByComparingTo("300000");
    }

    @Test
    void concurrentTransfersCannotOverdrawAnAccount() throws Exception {
        TestCustomer a = newCustomer(new BigDecimal("1000"));
        TestCustomer b = newCustomer(BigDecimal.ZERO);

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> {
                try {
                    send(a, b, "100", UUID.randomUUID().toString());
                    return true;
                } catch (FincoreException e) {
                    return false;
                }
            });
        }
        long succeeded = runConcurrently(tasks).stream().filter(Boolean::booleanValue).count();

        assertThat(succeeded).isEqualTo(10);
        assertThat(balanceOf(a.account())).isEqualByComparingTo("0");
        assertThat(balanceOf(b.account())).isEqualByComparingTo("1000");
        assertThat(reconciliation.reconcile().consistent()).isTrue();
    }

    @Test
    void opposingConcurrentTransfersDoNotDeadlock() throws Exception {
        TestCustomer a = newCustomer(new BigDecimal("10000"));
        TestCustomer b = newCustomer(new BigDecimal("10000"));

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> send(a, b, "100", UUID.randomUUID().toString()) != null);
            tasks.add(() -> send(b, a, "100", UUID.randomUUID().toString()) != null);
        }
        List<Boolean> results = runConcurrently(tasks);

        assertThat(results).doesNotContain(false).hasSize(20);
        assertThat(balanceOf(a.account())).isEqualByComparingTo("10000");
        assertThat(balanceOf(b.account())).isEqualByComparingTo("10000");
        assertThat(reconciliation.reconcile().consistent()).isTrue();
    }

    @Test
    void concurrentRetriesWithSameKeyCreateExactlyOneTransfer() throws Exception {
        TestCustomer a = newCustomer(new BigDecimal("1000"));
        TestCustomer b = newCustomer(BigDecimal.ZERO);
        String key = UUID.randomUUID().toString();

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> send(a, b, "100", key).replayed());
        }
        List<Boolean> replayedFlags = runConcurrently(tasks);

        assertThat(replayedFlags.stream().filter(replayed -> !replayed).count()).isEqualTo(1);
        assertThat(balanceOf(a.account())).isEqualByComparingTo("900");
    }

    @Test
    void completedTransferWritesOneOutboxEventThatTheRelayPublishes() {
        TestCustomer a = newCustomer(new BigDecimal("1000"));
        TestCustomer b = newCustomer(BigDecimal.ZERO);
        long pendingBefore = outboxRepository.countByStatus(OutboxStatus.PENDING);

        send(a, b, "100", UUID.randomUUID().toString());

        assertThat(outboxRepository.countByStatus(OutboxStatus.PENDING)).isGreaterThan(pendingBefore);
        outboxRelay.relayBatch();
        assertThat(outboxRepository.countByStatus(OutboxStatus.FAILED)).isZero();
    }
}
