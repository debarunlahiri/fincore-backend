package com.fincore.core.service;

import com.fincore.audit.service.AuditService;
import com.fincore.common.exception.FincoreException;
import com.fincore.core.config.FincoreProperties;
import com.fincore.core.dto.DepositQuote;
import com.fincore.core.entity.Account;
import com.fincore.core.entity.Direction;
import com.fincore.core.entity.FixedDeposit;
import com.fincore.core.entity.Journal;
import com.fincore.core.entity.JournalType;
import com.fincore.core.model.Posting;
import com.fincore.core.repository.AccountRepository;
import com.fincore.core.repository.FixedDepositRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Money moves to the bank on opening and back (with interest) on maturity or early closure, always
 * through the ledger.
 */
@Service
public class DepositService {

    private static final Logger log = LoggerFactory.getLogger(DepositService.class);

    private final FixedDepositRepository deposits;
    private final AccountService accountService;
    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final AuditService audit;
    private final FincoreProperties props;
    private final TransactionTemplate tx;

    public DepositService(
            FixedDepositRepository deposits,
            AccountService accountService,
            AccountRepository accounts,
            LedgerService ledger,
            AuditService audit,
            FincoreProperties props,
            TransactionTemplate tx) {
        this.deposits = deposits;
        this.accountService = accountService;
        this.accounts = accounts;
        this.ledger = ledger;
        this.audit = audit;
        this.props = props;
        this.tx = tx;
    }

    public DepositQuote quote(BigDecimal principal, int tenureMonths) {
        validate(principal, tenureMonths);
        BigDecimal rate = DepositCalculator.rateFor(tenureMonths);
        BigDecimal maturity = DepositCalculator.maturityAmount(principal, rate, tenureMonths);
        return new DepositQuote(
                principal,
                tenureMonths,
                rate,
                LocalDate.now(props.zone()).plusMonths(tenureMonths),
                maturity,
                maturity.subtract(principal));
    }

    @Transactional
    public FixedDeposit open(
            UUID userId, String accountNumber, BigDecimal principal, int tenureMonths) {
        validate(principal, tenureMonths);
        UUID accountId = accountService.ownedAccountId(userId, accountNumber);
        Map<UUID, Account> locked = lock(accountId);
        Account customer = locked.get(accountId);

        BigDecimal rate = DepositCalculator.rateFor(tenureMonths);
        BigDecimal maturity = DepositCalculator.maturityAmount(principal, rate, tenureMonths);
        Journal journal =
                ledger.post(
                        JournalType.FD_OPEN,
                        "Fixed deposit opened for " + tenureMonths + " months",
                        List.of(
                                new Posting(customer, Direction.DEBIT, principal),
                                new Posting(
                                        locked.get(CashService.SYSTEM_ACCOUNT_ID),
                                        Direction.CREDIT,
                                        principal)));
        FixedDeposit fd =
                deposits.save(
                        new FixedDeposit(
                                userId,
                                accountId,
                                customer.getAccountNumber(),
                                principal,
                                rate,
                                tenureMonths,
                                LocalDate.now(props.zone()),
                                maturity,
                                journal.getId()));
        audit.record(userId.toString(), "FD_OPENED", "FIXED_DEPOSIT", fd.getReference(), null);
        return fd;
    }

    @Transactional(readOnly = true)
    public List<FixedDeposit> list(UUID userId) {
        return deposits.findByOwnerUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public FixedDeposit get(UUID userId, String reference) {
        return deposits.findByReferenceAndOwnerUserId(reference.trim().toUpperCase(), userId)
                .orElseThrow(() -> FincoreException.notFound("Fixed deposit not found"));
    }

    /** Early closure: reduced interest for the full months elapsed (none before 3 months). */
    @Transactional
    public FixedDeposit closeEarly(UUID userId, String reference) {
        FixedDeposit fd =
                deposits.lockByReferenceAndOwner(reference.trim().toUpperCase(), userId)
                        .orElseThrow(() -> FincoreException.notFound("Fixed deposit not found"));
        if (fd.getStatus() != FixedDeposit.Status.ACTIVE) {
            throw FincoreException.conflict("FD_NOT_ACTIVE", "This deposit is already closed");
        }
        int elapsed =
                (int) Period.between(fd.getOpenedOn(), LocalDate.now(props.zone())).toTotalMonths();
        BigDecimal payout =
                DepositCalculator.prematurePayout(fd.getPrincipal(), fd.getAnnualRate(), elapsed);
        payOut(fd, payout, FixedDeposit.Status.CLOSED_EARLY, "Fixed deposit closed early");
        audit.record(
                userId.toString(),
                "FD_CLOSED_EARLY",
                "FIXED_DEPOSIT",
                fd.getReference(),
                "payout=" + payout);
        return fd;
    }

    /**
     * Credits every deposit that reached its maturity date. One transaction per deposit, so one
     * failure blocks no one.
     */
    public int processMaturities() {
        int done = 0;
        for (UUID id :
                deposits
                        .findByStatusAndMaturityDateLessThanEqual(
                                FixedDeposit.Status.ACTIVE, LocalDate.now(props.zone()))
                        .stream()
                        .map(FixedDeposit::getId)
                        .toList()) {
            try {
                tx.executeWithoutResult(status -> matureOne(id));
                done++;
            } catch (RuntimeException e) {
                log.warn(
                        "Maturity of deposit {} failed, will retry next run: {}", id, e.toString());
            }
        }
        return done;
    }

    private void matureOne(UUID id) {
        FixedDeposit fd = deposits.lockById(id).orElse(null);
        if (fd == null || fd.getStatus() != FixedDeposit.Status.ACTIVE) {
            return;
        }
        payOut(fd, fd.getMaturityAmount(), FixedDeposit.Status.MATURED, "Fixed deposit matured");
    }

    private void payOut(
            FixedDeposit fd, BigDecimal payout, FixedDeposit.Status status, String description) {
        Map<UUID, Account> locked = lock(fd.getAccountId());
        Journal journal =
                ledger.post(
                        JournalType.FD_CLOSE,
                        description + " (" + fd.getReference() + ")",
                        List.of(
                                new Posting(
                                        locked.get(CashService.SYSTEM_ACCOUNT_ID),
                                        Direction.DEBIT,
                                        payout),
                                new Posting(
                                        locked.get(fd.getAccountId()), Direction.CREDIT, payout)));
        fd.close(status, payout, journal.getId());
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

    private static void validate(BigDecimal principal, int months) {
        if (principal.compareTo(DepositCalculator.MIN_PRINCIPAL) < 0
                || principal.compareTo(DepositCalculator.MAX_PRINCIPAL) > 0) {
            throw FincoreException.badRequest(
                    "INVALID_PRINCIPAL", "Deposit amount must be between 1,000 and 1,00,00,000");
        }
        if (months < DepositCalculator.MIN_MONTHS || months > DepositCalculator.MAX_MONTHS) {
            throw FincoreException.badRequest(
                    "INVALID_TENURE", "Tenure must be between 3 and 120 months");
        }
    }
}
