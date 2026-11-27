package com.fincore.core.service;

import com.fincore.common.exception.FincoreException;
import com.fincore.core.config.FincoreProperties;
import com.fincore.core.dto.TransferRequest;
import com.fincore.core.dto.TransferResponse;
import com.fincore.core.dto.TransferResult;
import com.fincore.core.entity.Account;
import com.fincore.core.entity.AccountType;
import com.fincore.core.entity.Direction;
import com.fincore.core.entity.Journal;
import com.fincore.core.entity.JournalType;
import com.fincore.core.entity.Transfer;
import com.fincore.core.event.TransferCompleted;
import com.fincore.core.model.Posting;
import com.fincore.core.repository.AccountRepository;
import com.fincore.core.repository.TransferRepository;
import com.fincore.core.rule.FraudRule;
import com.fincore.outbox.service.OutboxService;
import com.fincore.security.model.AuthenticatedUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One database transaction per transfer attempt: lock, validate, post ledger entries, record the
 * transfer and the outbox event. Everything commits together or not at all. Kept separate from
 * {@link TransferService} so the transaction proxy applies and a failed commit (e.g. idempotency
 * unique violation) surfaces to the caller, which can then replay the stored result.
 */
@Component
public class TransferExecutor {

    private final TransferRepository transfers;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final FraudService fraud;
    private final LedgerService ledger;
    private final OutboxService outbox;
    private final FincoreProperties props;

    TransferExecutor(
            TransferRepository transfers,
            AccountRepository accounts,
            AccountService accountService,
            FraudService fraud,
            LedgerService ledger,
            OutboxService outbox,
            FincoreProperties props) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.accountService = accountService;
        this.fraud = fraud;
        this.ledger = ledger;
        this.outbox = outbox;
        this.props = props;
    }

    @Transactional
    public TransferResult execute(
            AuthenticatedUser user,
            String idempotencyKey,
            TransferRequest req,
            String requestHash) {
        // 1. idempotency: same key + same payload => return the original result, different payload
        // => error
        var existing = transfers.findByInitiatedByAndIdempotencyKey(user.userId(), idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        // 2. cheap scalar checks WITHOUT loading account entities (they must first be read under
        // the row lock)
        UUID sourceId = accountService.ownedAccountId(user.userId(), req.sourceAccountNumber());
        UUID targetId =
                accounts.findIdByAccountNumber(req.targetAccountNumber().trim())
                        .orElseThrow(() -> FincoreException.notFound("Target account not found"));
        if (targetId.equals(sourceId)) {
            throw FincoreException.badRequest(
                    "SAME_ACCOUNT", "Source and target account must differ");
        }

        // 3. fraud rules before we take any locks
        fraud.assertAllowed(new FraudRule.TransferAttempt(sourceId, targetId, req.amount()));

        // 4. lock both accounts in a deterministic order (ORDER BY id) => no lock-order deadlocks
        Map<UUID, Account> locked =
                accounts.lockAllOrdered(List.of(sourceId, targetId)).stream()
                        .collect(Collectors.toMap(Account::getId, Function.identity()));
        Account source = locked.get(sourceId);
        Account target = locked.get(targetId);
        if (source == null || target == null) {
            throw FincoreException.notFound("Account not found");
        }
        if (target.getType() == AccountType.SYSTEM) {
            throw FincoreException.badRequest(
                    "INVALID_TARGET", "Cannot transfer to a system account");
        }

        String currency =
                req.currency() == null
                        ? AccountService.DEFAULT_CURRENCY
                        : req.currency().toUpperCase();
        if (!currency.equals(source.getCurrency()) || !currency.equals(target.getCurrency())) {
            throw FincoreException.unprocessable(
                    "CURRENCY_MISMATCH", "Cross-currency transfers are not supported");
        }

        // 5. daily limit, evaluated under the source lock so concurrent transfers cannot jointly
        // exceed it
        Instant startOfDay = LocalDate.now(props.zone()).atStartOfDay(props.zone()).toInstant();
        BigDecimal usedToday = transfers.sumAmountSince(source.getId(), startOfDay);
        if (usedToday.add(req.amount()).compareTo(source.getDailyTransferLimit()) > 0) {
            throw FincoreException.unprocessable(
                    "DAILY_LIMIT_EXCEEDED", "Daily transfer limit exceeded");
        }

        // 6. balanced debit + credit postings (insufficient funds is raised by the debit)
        Journal journal =
                ledger.post(
                        JournalType.TRANSFER,
                        "Transfer "
                                + source.getAccountNumber()
                                + " -> "
                                + target.getAccountNumber(),
                        List.of(
                                new Posting(source, Direction.DEBIT, req.amount()),
                                new Posting(target, Direction.CREDIT, req.amount())));

        // 7. transfer row + outbox event in the same transaction; flush so a duplicate key fails
        // right here
        Transfer transfer =
                transfers.saveAndFlush(
                        new Transfer(
                                idempotencyKey,
                                user.userId(),
                                source.getId(),
                                target.getId(),
                                req.amount(),
                                currency,
                                req.remarks(),
                                requestHash,
                                journal.getId(),
                                journal.getReference(),
                                source.getAccountNumber(),
                                target.getAccountNumber()));

        outbox.record(
                "TRANSFER",
                transfer.getId(),
                "TransferCompleted",
                new TransferCompleted(
                        transfer.getId(),
                        source.getId(),
                        target.getId(),
                        req.amount(),
                        currency,
                        journal.getId(),
                        Instant.now()));

        return new TransferResult(TransferResponse.from(transfer), false);
    }

    @Transactional(readOnly = true)
    public TransferResult replayFor(
            AuthenticatedUser user, String idempotencyKey, String requestHash) {
        Transfer existing =
                transfers
                        .findByInitiatedByAndIdempotencyKey(user.userId(), idempotencyKey)
                        .orElse(null);
        return existing == null ? null : replay(existing, requestHash);
    }

    private TransferResult replay(Transfer existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw FincoreException.unprocessable(
                    "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used with a different request");
        }
        return new TransferResult(TransferResponse.from(existing), true);
    }
}
