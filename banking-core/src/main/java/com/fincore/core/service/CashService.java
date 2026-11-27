package com.fincore.core.service;

import com.fincore.common.exception.FincoreException;
import com.fincore.core.entity.Account;
import com.fincore.core.entity.Direction;
import com.fincore.core.entity.Journal;
import com.fincore.core.entity.JournalType;
import com.fincore.core.model.Posting;
import com.fincore.core.repository.AccountRepository;
import com.fincore.outbox.service.OutboxService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Simulated cash-in / cash-out against the system settlement account. */
@Service
public class CashService {

    public static final UUID SYSTEM_ACCOUNT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    public record CashResult(
            String reference, UUID accountId, String accountNumber, BigDecimal balance) {}

    record CashMovement(
            UUID accountId,
            UUID journalId,
            BigDecimal amount,
            String currency,
            Instant occurredAt) {}

    private final AccountService accountService;
    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final OutboxService outbox;

    public CashService(
            AccountService accountService,
            AccountRepository accounts,
            LedgerService ledger,
            OutboxService outbox) {
        this.accountService = accountService;
        this.accounts = accounts;
        this.ledger = ledger;
        this.outbox = outbox;
    }

    @Transactional
    public CashResult deposit(UUID userId, UUID accountId, BigDecimal amount) {
        return move(userId, accountId, amount, JournalType.DEPOSIT, "MoneyDeposited");
    }

    @Transactional
    public CashResult withdraw(UUID userId, UUID accountId, BigDecimal amount) {
        return move(userId, accountId, amount, JournalType.WITHDRAWAL, "MoneyWithdrawn");
    }

    private CashResult move(
            UUID userId, UUID accountId, BigDecimal amount, JournalType type, String eventType) {
        // scalar ownership check first: it must not load the Account entity before the row lock is
        // taken
        accountService.assertOwned(userId, accountId);

        Map<UUID, Account> locked =
                accounts.lockAllOrdered(List.of(accountId, SYSTEM_ACCOUNT_ID)).stream()
                        .collect(Collectors.toMap(Account::getId, Function.identity()));
        Account customer = locked.get(accountId);
        Account system = locked.get(SYSTEM_ACCOUNT_ID);
        if (customer == null || system == null) {
            throw FincoreException.notFound("Account not found");
        }

        boolean deposit = type == JournalType.DEPOSIT;
        Journal journal =
                ledger.post(
                        type,
                        deposit ? "Cash deposit" : "Cash withdrawal",
                        List.of(
                                new Posting(
                                        system,
                                        deposit ? Direction.DEBIT : Direction.CREDIT,
                                        amount),
                                new Posting(
                                        customer,
                                        deposit ? Direction.CREDIT : Direction.DEBIT,
                                        amount)));

        outbox.record(
                "ACCOUNT",
                accountId,
                eventType,
                new CashMovement(
                        accountId, journal.getId(), amount, customer.getCurrency(), Instant.now()));
        return new CashResult(
                journal.getReference(),
                accountId,
                customer.getAccountNumber(),
                customer.getBalance());
    }
}
