package com.fincore.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fincore.common.exception.FincoreException;
import com.fincore.core.entity.Account;
import com.fincore.core.entity.AccountType;
import com.fincore.core.entity.Direction;
import com.fincore.core.model.Posting;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure unit tests of the double-entry rules; no Spring context or database needed. */
public class LedgerServiceTest {

    private final LedgerService ledger = new LedgerService(null, null);

    private static Account account(String number, String currency) {
        return new Account(
                number, UUID.randomUUID(), AccountType.SAVINGS, currency, new BigDecimal("100000"));
    }

    @Test
    void balancedPostingsAreAccepted() {
        Account a = account("A", "INR");
        Account b = account("B", "INR");
        assertThatCode(
                        () ->
                                ledger.validate(
                                        List.of(
                                                new Posting(
                                                        a,
                                                        Direction.DEBIT,
                                                        new BigDecimal("5000.00")),
                                                new Posting(
                                                        b,
                                                        Direction.CREDIT,
                                                        new BigDecimal("5000.00")))))
                .doesNotThrowAnyException();
    }

    @Test
    void unbalancedPostingsAreRejected() {
        Account a = account("A", "INR");
        Account b = account("B", "INR");
        assertThatThrownBy(
                        () ->
                                ledger.validate(
                                        List.of(
                                                new Posting(
                                                        a,
                                                        Direction.DEBIT,
                                                        new BigDecimal("5000.00")),
                                                new Posting(
                                                        b,
                                                        Direction.CREDIT,
                                                        new BigDecimal("4999.99")))))
                .isInstanceOf(FincoreException.class)
                .extracting(e -> ((FincoreException) e).code())
                .isEqualTo("UNBALANCED_JOURNAL");
    }

    @Test
    void singlePostingIsRejected() {
        Account a = account("A", "INR");
        assertThatThrownBy(
                        () ->
                                ledger.validate(
                                        List.of(new Posting(a, Direction.DEBIT, BigDecimal.TEN))))
                .isInstanceOf(FincoreException.class);
    }

    @Test
    void nonPositiveAmountIsRejected() {
        Account a = account("A", "INR");
        Account b = account("B", "INR");
        assertThatThrownBy(
                        () ->
                                ledger.validate(
                                        List.of(
                                                new Posting(a, Direction.DEBIT, BigDecimal.ZERO),
                                                new Posting(b, Direction.CREDIT, BigDecimal.ZERO))))
                .isInstanceOf(FincoreException.class);
    }

    @Test
    void frozenAccountCannotBePostedTo() {
        Account a = account("A", "INR");
        Account b = account("B", "INR");
        b.freeze();
        assertThatThrownBy(
                        () ->
                                ledger.validate(
                                        List.of(
                                                new Posting(a, Direction.DEBIT, BigDecimal.TEN),
                                                new Posting(b, Direction.CREDIT, BigDecimal.TEN))))
                .isInstanceOf(FincoreException.class)
                .extracting(e -> ((FincoreException) e).code())
                .isEqualTo("ACCOUNT_NOT_ACTIVE");
    }

    @Test
    void customerAccountCannotGoNegativeButSystemAccountCan() {
        Account customer = account("C", "INR");
        assertThatThrownBy(() -> customer.debit(BigDecimal.ONE))
                .isInstanceOf(FincoreException.class)
                .extracting(e -> ((FincoreException) e).code())
                .isEqualTo("INSUFFICIENT_FUNDS");

        Account system = new Account("S", null, AccountType.SYSTEM, "INR", BigDecimal.ZERO);
        system.debit(new BigDecimal("50"));
        assertThat(system.getBalance()).isEqualByComparingTo("-50");
    }
}
