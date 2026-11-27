package com.fincore.core.service;

import com.fincore.common.exception.FincoreException;
import com.fincore.core.entity.Account;
import com.fincore.core.entity.Direction;
import com.fincore.core.entity.Journal;
import com.fincore.core.entity.JournalType;
import com.fincore.core.model.Posting;
import com.fincore.core.repository.AccountRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ledger operations on behalf of the payment-service's Saga. Both operations are idempotent on a
 * caller-chosen reference ({@code PAY-DEBIT-<paymentId>}, {@code PAY-REV-<paymentId>}) stored in
 * {@code external_postings}: the payment-service may retry after a timeout or crash without ever
 * double-debiting or double-refunding. The reversal derives account and amount from the stored
 * original posting, never from caller input.
 */
@Service
public class PaymentLedgerService {

    public record Posted(UUID journalId, boolean replayed) {}

    private record StoredPosting(UUID journalId, UUID accountId, BigDecimal amount) {}

    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final AccountService accountService;
    private final AccountRepository accounts;
    private final LedgerService ledger;

    public PaymentLedgerService(
            TransactionTemplate tx,
            JdbcClient jdbc,
            AccountService accountService,
            AccountRepository accounts,
            LedgerService ledger) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.accountService = accountService;
        this.accounts = accounts;
        this.ledger = ledger;
    }

    public Posted debit(
            UUID paymentId, UUID userId, UUID accountId, BigDecimal amount, String description) {
        String reference = "PAY-DEBIT-" + paymentId;
        try {
            return tx.execute(status -> doDebit(reference, userId, accountId, amount, description));
        } catch (DataIntegrityViolationException race) {
            // a concurrent identical request won the unique reference; our whole transaction rolled
            // back
            return find(reference)
                    .map(p -> new Posted(p.journalId(), true))
                    .orElseThrow(() -> race);
        }
    }

    public Posted reverse(UUID paymentId) {
        String reference = "PAY-REV-" + paymentId;
        try {
            return tx.execute(status -> doReverse(reference, paymentId));
        } catch (DataIntegrityViolationException race) {
            return find(reference)
                    .map(p -> new Posted(p.journalId(), true))
                    .orElseThrow(() -> race);
        }
    }

    private Posted doDebit(
            String reference, UUID userId, UUID accountId, BigDecimal amount, String description) {
        var existing = find(reference);
        if (existing.isPresent()) {
            return new Posted(existing.get().journalId(), true);
        }
        accountService.assertOwned(userId, accountId);
        Map<UUID, Account> locked = lock(accountId);
        Journal journal =
                ledger.post(
                        JournalType.PAYMENT,
                        description,
                        List.of(
                                new Posting(locked.get(accountId), Direction.DEBIT, amount),
                                new Posting(
                                        locked.get(CashService.SYSTEM_ACCOUNT_ID),
                                        Direction.CREDIT,
                                        amount)));
        store(reference, "DEBIT", accountId, amount, journal.getId());
        return new Posted(journal.getId(), false);
    }

    private Posted doReverse(String reference, UUID paymentId) {
        var existing = find(reference);
        if (existing.isPresent()) {
            return new Posted(existing.get().journalId(), true);
        }
        StoredPosting original =
                find("PAY-DEBIT-" + paymentId)
                        .orElseThrow(
                                () ->
                                        FincoreException.notFound(
                                                "No debit was recorded for payment " + paymentId));
        Map<UUID, Account> locked = lock(original.accountId());
        Journal journal =
                ledger.post(
                        JournalType.PAYMENT_REVERSAL,
                        "Reversal of payment " + paymentId,
                        List.of(
                                new Posting(
                                        locked.get(CashService.SYSTEM_ACCOUNT_ID),
                                        Direction.DEBIT,
                                        original.amount()),
                                new Posting(
                                        locked.get(original.accountId()),
                                        Direction.CREDIT,
                                        original.amount())));
        store(reference, "REVERSAL", original.accountId(), original.amount(), journal.getId());
        return new Posted(journal.getId(), false);
    }

    private Map<UUID, Account> lock(UUID accountId) {
        Map<UUID, Account> locked =
                accounts.lockAllOrdered(List.of(accountId, CashService.SYSTEM_ACCOUNT_ID)).stream()
                        .collect(Collectors.toMap(Account::getId, Function.identity()));
        if (!locked.containsKey(accountId) || !locked.containsKey(CashService.SYSTEM_ACCOUNT_ID)) {
            throw FincoreException.notFound("Account not found");
        }
        return locked;
    }

    private Optional<StoredPosting> find(String reference) {
        return jdbc.sql(
                        "select journal_id, account_id, amount from external_postings where reference = ?")
                .param(reference)
                .query(StoredPosting.class)
                .optional();
    }

    private void store(
            String reference, String kind, UUID accountId, BigDecimal amount, UUID journalId) {
        jdbc.sql(
                        "insert into external_postings (reference, kind, account_id, amount, journal_id, created_at)"
                                + " values (?, ?, ?, ?, ?, now())")
                .params(reference, kind, accountId, amount, journalId)
                .update();
    }
}
