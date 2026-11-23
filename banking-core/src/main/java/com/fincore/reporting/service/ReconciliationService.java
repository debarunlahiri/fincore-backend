package com.fincore.reporting.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Independent audit of the books: recomputes every balance from the immutable ledger and checks that each
 * journal balances and that all accounts together sum to zero. Any finding means data corruption or a bug.
 */
@Service
public class ReconciliationService {

    public record BalanceMismatch(UUID accountId, String accountNumber, BigDecimal storedBalance, BigDecimal ledgerBalance) {}

    public record UnbalancedJournal(UUID journalId, String currency, BigDecimal net) {}

    public record Report(
            boolean consistent,
            BigDecimal sumOfAllBalances,
            List<BalanceMismatch> balanceMismatches,
            List<UnbalancedJournal> unbalancedJournals) {}

    private static final String SIGNED = "case when e.direction = 'CREDIT' then e.amount else -e.amount end";

    private final JdbcClient jdbc;

    public ReconciliationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Report reconcile() {
        List<BalanceMismatch> mismatches = jdbc.sql("""
                        select a.id as account_id, a.account_number, a.balance as stored_balance,
                               coalesce(sum(%s), 0) as ledger_balance
                        from accounts a
                        left join ledger_entries e on e.account_id = a.id
                        group by a.id, a.account_number, a.balance
                        having a.balance <> coalesce(sum(%s), 0)
                        """.formatted(SIGNED, SIGNED))
                .query(BalanceMismatch.class)
                .list();

        List<UnbalancedJournal> unbalanced = jdbc.sql("""
                        select e.journal_id, e.currency, sum(%s) as net
                        from ledger_entries e
                        group by e.journal_id, e.currency
                        having sum(%s) <> 0
                        """.formatted(SIGNED, SIGNED))
                .query(UnbalancedJournal.class)
                .list();

        BigDecimal total = jdbc.sql("select coalesce(sum(balance), 0) from accounts").query(BigDecimal.class).single();

        boolean consistent = mismatches.isEmpty() && unbalanced.isEmpty() && total.signum() == 0;
        return new Report(consistent, total, mismatches, unbalanced);
    }
}
