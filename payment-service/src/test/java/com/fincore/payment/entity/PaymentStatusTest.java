package com.fincore.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fincore.common.exception.FincoreException;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class PaymentStatusTest {

    private static Payment payment() {
        return new Payment(
                UUID.randomUUID(), "key", UUID.randomUUID(), Rail.UPI, "shop@upi", new BigDecimal("10"), "INR", "hash");
    }

    @Test
    void onlyTheDocumentedTransitionsAreAllowed() {
        assertThat(PaymentStatus.REQUESTED.canTransitionTo(PaymentStatus.DEBITED)).isTrue();
        assertThat(PaymentStatus.REQUESTED.canTransitionTo(PaymentStatus.FAILED)).isTrue();
        assertThat(PaymentStatus.REQUESTED.canTransitionTo(PaymentStatus.SETTLED)).isFalse();
        assertThat(PaymentStatus.REQUESTED.canTransitionTo(PaymentStatus.REFUNDED)).isFalse();
        assertThat(PaymentStatus.DEBITED.canTransitionTo(PaymentStatus.SETTLED)).isTrue();
        assertThat(PaymentStatus.DEBITED.canTransitionTo(PaymentStatus.REFUNDED)).isTrue();
        assertThat(PaymentStatus.DEBITED.canTransitionTo(PaymentStatus.FAILED)).isFalse();
        assertThat(PaymentStatus.DEBITED.canTransitionTo(PaymentStatus.REQUESTED)).isFalse();
    }

    @Test
    void terminalStatesCannotMoveAnywhere() {
        for (PaymentStatus terminal : EnumSet.of(PaymentStatus.SETTLED, PaymentStatus.REFUNDED, PaymentStatus.FAILED)) {
            assertThat(terminal.isTerminal()).isTrue();
            for (PaymentStatus next : PaymentStatus.values()) {
                assertThat(terminal.canTransitionTo(next)).as("%s -> %s", terminal, next).isFalse();
            }
        }
        assertThat(PaymentStatus.REQUESTED.isTerminal()).isFalse();
        assertThat(PaymentStatus.DEBITED.isTerminal()).isFalse();
    }

    @Test
    void happyPathWalksRequestedDebitedSettled() {
        Payment p = payment();
        UUID journal = UUID.randomUUID();

        p.markDebited(journal);
        p.markSettled();

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(p.getDebitJournalId()).isEqualTo(journal);
    }

    @Test
    void compensationRecordsReasonAndReversalJournal() {
        Payment p = payment();
        UUID reversal = UUID.randomUUID();
        p.markDebited(UUID.randomUUID());

        p.markRefunded("Declined", reversal);

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(p.getFailureReason()).isEqualTo("Declined");
        assertThat(p.getReversalJournalId()).isEqualTo(reversal);
    }

    @Test
    void illegalTransitionsThrowAConflict() {
        Payment settled = payment();
        settled.markDebited(UUID.randomUUID());
        settled.markSettled();

        assertThatThrownBy(() -> settled.markRefunded("late", UUID.randomUUID()))
                .isInstanceOf(FincoreException.class)
                .satisfies(e -> assertThat(((FincoreException) e).code()).isEqualTo("INVALID_PAYMENT_STATE"));
        assertThatThrownBy(() -> payment().markSettled()).isInstanceOf(FincoreException.class);
    }

    @Test
    void railCeilingsAreOrdered() {
        assertThat(Rail.UPI.maxAmount()).isLessThan(Rail.IMPS.maxAmount());
        assertThat(Rail.IMPS.maxAmount()).isLessThan(Rail.NEFT.maxAmount());
    }
}
