package com.fincore.ledger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fincore.AbstractIntegrationTest;
import com.fincore.common.exception.FincoreException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The database is the last line of defence: these rules must hold even if application code is buggy. */
public class LedgerDatabaseRulesIntegrationTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Test
    void ledgerEntriesCannotBeUpdatedOrDeleted() {
        TestCustomer c = newCustomer(new BigDecimal("100"));
        UUID entryId = jdbc.queryForObject(
                "select id from ledger_entries where account_id = ? limit 1", UUID.class, c.account().getId());

        assertThatThrownBy(() -> jdbc.update("update ledger_entries set amount = 999 where id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from ledger_entries where id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void journalsAndAuditLogAreAppendOnlyToo() {
        newCustomer(new BigDecimal("100"));

        assertThatThrownBy(() -> jdbc.update("update journals set description = 'x'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from journals")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("update audit_log set action = 'x'")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void customerBalanceCannotBeForcedNegativeEvenWithRawSql() {
        TestCustomer c = newCustomer(new BigDecimal("100"));

        assertThatThrownBy(() -> jdbc.update("update accounts set balance = -1 where id = ?", c.account().getId()))
                .isInstanceOf(DataAccessException.class);
        assertThat(balanceOf(c.account())).isEqualByComparingTo("100");
    }

    @Test
    void negativeOrZeroLedgerAmountsAreRejectedByTheDatabase() {
        TestCustomer c = newCustomer(new BigDecimal("100"));
        UUID journalId = jdbc.queryForObject(
                "select journal_id from ledger_entries where account_id = ? limit 1", UUID.class, c.account().getId());

        assertThatThrownBy(() -> jdbc.update(
                        "insert into ledger_entries (id, journal_id, account_id, direction, amount, currency, balance_after, created_at)"
                                + " values (?, ?, ?, 'CREDIT', 0, 'INR', 0, now())",
                        UUID.randomUUID(), journalId, c.account().getId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void reconciliationDetectsADriftedBalanceAndRecoversWhenFixed() {
        TestCustomer c = newCustomer(new BigDecimal("100"));
        jdbc.update("update accounts set balance = balance + 5 where id = ?", c.account().getId());
        try {
            var report = reconciliation.reconcile();

            assertThat(report.consistent()).isFalse();
            assertThat(report.balanceMismatches())
                    .anySatisfy(m -> assertThat(m.accountId()).isEqualTo(c.account().getId()));
        } finally {
            jdbc.update("update accounts set balance = balance - 5 where id = ?", c.account().getId());
        }
        assertThat(reconciliation.reconcile().consistent()).isTrue();
    }

    @Test
    void sumOfAllBalancesIsAlwaysZero() {
        newCustomer(new BigDecimal("12345.67"));
        newCustomer(new BigDecimal("0.01"));

        assertThat(reconciliation.reconcile().sumOfAllBalances()).isEqualByComparingTo("0");
    }

    @Test
    void depositAndWithdrawRoundTripLeavesTheBalanceUnchanged() {
        TestCustomer c = newCustomer(new BigDecimal("100"));

        cash.deposit(c.user().userId(), c.account().getId(), new BigDecimal("40.50"));
        cash.withdraw(c.user().userId(), c.account().getId(), new BigDecimal("40.50"));

        assertThat(balanceOf(c.account())).isEqualByComparingTo("100");
    }

    @Test
    void withdrawingMoreThanTheBalanceFails() {
        TestCustomer c = newCustomer(new BigDecimal("100"));

        assertThatThrownBy(() -> cash.withdraw(c.user().userId(), c.account().getId(), new BigDecimal("100.01")))
                .isInstanceOf(FincoreException.class)
                .satisfies(e -> assertThat(((FincoreException) e).code()).isEqualTo("INSUFFICIENT_FUNDS"));
        assertThat(balanceOf(c.account())).isEqualByComparingTo("100");
    }

    @Test
    void concurrentWithdrawalsCannotOverdrawTheAccount() throws Exception {
        TestCustomer c = newCustomer(new BigDecimal("500"));

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            tasks.add(() -> {
                try {
                    cash.withdraw(c.user().userId(), c.account().getId(), new BigDecimal("100"));
                    return true;
                } catch (FincoreException e) {
                    return false;
                }
            });
        }
        long succeeded = runConcurrently(tasks).stream().filter(Boolean::booleanValue).count();

        assertThat(succeeded).isEqualTo(5);
        assertThat(balanceOf(c.account())).isEqualByComparingTo("0");
        assertThat(reconciliation.reconcile().consistent()).isTrue();
    }

    @Test
    void concurrentDepositsAreAllApplied() throws Exception {
        TestCustomer c = newCustomer(BigDecimal.ZERO);

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            tasks.add(() -> {
                cash.deposit(c.user().userId(), c.account().getId(), new BigDecimal("10"));
                return true;
            });
        }
        runConcurrently(tasks);

        assertThat(balanceOf(c.account())).isEqualByComparingTo("150");
        assertThat(reconciliation.reconcile().consistent()).isTrue();
    }
}
