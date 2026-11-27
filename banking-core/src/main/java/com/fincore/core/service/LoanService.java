package com.fincore.core.service;

import com.fincore.audit.service.AuditService;
import com.fincore.common.exception.FincoreException;
import com.fincore.core.client.CustomerClient;
import com.fincore.core.config.FincoreProperties;
import com.fincore.core.dto.InstallmentResponse;
import com.fincore.core.dto.LoanDetailResponse;
import com.fincore.core.dto.LoanQuote;
import com.fincore.core.dto.LoanResponse;
import com.fincore.core.dto.OtpChallengeResponse;
import com.fincore.core.dto.OtpProof;
import com.fincore.core.entity.Account;
import com.fincore.core.entity.Direction;
import com.fincore.core.entity.Journal;
import com.fincore.core.entity.JournalType;
import com.fincore.core.entity.Loan;
import com.fincore.core.entity.LoanInstallment;
import com.fincore.core.entity.LoanType;
import com.fincore.core.model.Posting;
import com.fincore.core.repository.AccountRepository;
import com.fincore.core.repository.LoanInstallmentRepository;
import com.fincore.core.repository.LoanRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.EnumSet;
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
 * Loan lifecycle: apply (instant underwriting) -> sanctioned (APPROVED) -> customer accepts with an
 * OTP -> disbursed into the account (ACTIVE, EMI schedule created) -> monthly EMIs -> CLOSED, or
 * foreclosed early. Underwriting is simulated: KYC must be verified, total EMIs of the customer may
 * not exceed half the declared monthly income, and at most 3 loans may be open at once.
 */
@Service
public class LoanService {

    public static final String OTP_PURPOSE = "LOAN_ACCEPT";
    private static final BigDecimal MAX_FOIR = new BigDecimal("0.50");
    private static final BigDecimal LATE_FEE_RATE = new BigDecimal("0.02");
    private static final int MAX_OPEN_LOANS = 3;
    private static final EnumSet<Loan.Status> OPEN =
            EnumSet.of(Loan.Status.APPROVED, Loan.Status.ACTIVE);
    private static final Logger log = LoggerFactory.getLogger(LoanService.class);

    private final LoanRepository loans;
    private final LoanInstallmentRepository installments;
    private final AccountService accountService;
    private final AccountRepository accounts;
    private final CustomerClient customers;
    private final LedgerService ledger;
    private final OtpService otp;
    private final AuditService audit;
    private final FincoreProperties props;
    private final TransactionTemplate tx;

    public LoanService(
            LoanRepository loans,
            LoanInstallmentRepository installments,
            AccountService accountService,
            AccountRepository accounts,
            CustomerClient customers,
            LedgerService ledger,
            OtpService otp,
            AuditService audit,
            FincoreProperties props,
            TransactionTemplate tx) {
        this.tx = tx;
        this.loans = loans;
        this.installments = installments;
        this.accountService = accountService;
        this.accounts = accounts;
        this.customers = customers;
        this.ledger = ledger;
        this.otp = otp;
        this.audit = audit;
        this.props = props;
    }

    public LoanQuote quote(LoanType type, BigDecimal principal, int months) {
        validateTerms(type, principal, months);
        var rows =
                LoanCalculator.schedule(
                        principal, type.rate(), months, LocalDate.now(props.zone()).plusMonths(1));
        BigDecimal interest = LoanCalculator.totalInterest(rows);
        return new LoanQuote(
                type,
                principal,
                months,
                type.rate(),
                LoanCalculator.emi(principal, type.rate(), months),
                fee(type, principal),
                interest,
                principal.add(interest));
    }

    /** KYC is a remote call, so it happens before the database transaction. */
    public Loan apply(
            UUID userId,
            String accountNumber,
            LoanType type,
            BigDecimal principal,
            int months,
            BigDecimal income) {
        validateTerms(type, principal, months);
        if (!customers.byUser(userId).isKycVerified()) {
            throw FincoreException.unprocessable(
                    "KYC_REQUIRED", "KYC must be verified before applying for a loan");
        }
        return tx.execute(status -> decide(userId, accountNumber, type, principal, months, income));
    }

    private Loan decide(
            UUID userId,
            String accountNumber,
            LoanType type,
            BigDecimal principal,
            int months,
            BigDecimal income) {
        Account account = accountService.requireOwnedByNumber(userId, accountNumber);
        BigDecimal emi = LoanCalculator.emi(principal, type.rate(), months);

        String rejection = null;
        if (loans.countByOwnerUserIdAndStatusIn(userId, OPEN) >= MAX_OPEN_LOANS) {
            rejection = "You already have the maximum number of open loans";
        } else if (loans.sumEmi(userId, EnumSet.of(Loan.Status.ACTIVE))
                        .add(emi)
                        .compareTo(income.multiply(MAX_FOIR))
                > 0) {
            rejection = "Total EMIs would exceed 50% of your monthly income";
        }
        Loan loan =
                loans.save(
                        new Loan(
                                userId,
                                account.getId(),
                                account.getAccountNumber(),
                                type,
                                principal,
                                type.rate(),
                                months,
                                emi,
                                income,
                                fee(type, principal),
                                rejection == null ? Loan.Status.APPROVED : Loan.Status.REJECTED,
                                rejection));
        audit.record(
                userId.toString(),
                rejection == null ? "LOAN_APPROVED" : "LOAN_REJECTED",
                "LOAN",
                loan.getReference(),
                rejection);
        return loan;
    }

    public OtpChallengeResponse requestAcceptanceOtp(UUID userId, String reference) {
        Loan loan = get(userId, reference);
        if (loan.getStatus() != Loan.Status.APPROVED) {
            throw FincoreException.conflict(
                    "LOAN_NOT_APPROVED", "Only a sanctioned loan can be accepted");
        }
        return otp.issue(userId, OTP_PURPOSE, loan.getReference());
    }

    /**
     * Accepting needs an OTP (verified first in its own transaction), then the money is disbursed.
     */
    public Loan accept(UUID userId, String reference, OtpProof proof) {
        String ref = reference.trim().toUpperCase();
        otp.verify(userId, OTP_PURPOSE, ref, proof);
        return tx.execute(status -> disburse(userId, ref));
    }

    private Loan disburse(UUID userId, String reference) {
        Loan loan =
                loans.lockByReferenceAndOwner(reference, userId)
                        .orElseThrow(() -> FincoreException.notFound("Loan not found"));
        if (loan.getStatus() != Loan.Status.APPROVED) {
            throw FincoreException.conflict(
                    "LOAN_NOT_APPROVED", "Only a sanctioned loan can be accepted");
        }
        Map<UUID, Account> locked = lock(loan.getAccountId());
        BigDecimal net = loan.getPrincipal().subtract(loan.getProcessingFee());
        Journal journal =
                ledger.post(
                        JournalType.LOAN_DISBURSAL,
                        "Loan "
                                + loan.getReference()
                                + " disbursed (net of processing fee "
                                + loan.getProcessingFee()
                                + ")",
                        List.of(
                                new Posting(
                                        locked.get(CashService.SYSTEM_ACCOUNT_ID),
                                        Direction.DEBIT,
                                        net),
                                new Posting(
                                        locked.get(loan.getAccountId()), Direction.CREDIT, net)));
        loan.disburse(journal.getId());

        LocalDate firstDue = LocalDate.now(props.zone()).plusMonths(1);
        installments.saveAll(
                LoanCalculator.schedule(
                                loan.getPrincipal(),
                                loan.getAnnualRate(),
                                loan.getTenureMonths(),
                                firstDue)
                        .stream()
                        .map(
                                r ->
                                        new LoanInstallment(
                                                loan.getId(),
                                                r.number(),
                                                r.dueDate(),
                                                r.emi(),
                                                r.principalPart(),
                                                r.interestPart()))
                        .toList());
        audit.record(userId.toString(), "LOAN_DISBURSED", "LOAN", loan.getReference(), null);
        return loan;
    }

    @Transactional
    public Loan decline(UUID userId, String reference) {
        Loan loan =
                loans.lockByReferenceAndOwner(reference.trim().toUpperCase(), userId)
                        .orElseThrow(() -> FincoreException.notFound("Loan not found"));
        if (loan.getStatus() != Loan.Status.APPROVED) {
            throw FincoreException.conflict(
                    "LOAN_NOT_APPROVED", "Only a sanctioned loan can be declined");
        }
        loan.decline();
        return loan;
    }

    /**
     * Pays the earliest unpaid instalment (plus any late fee). Repayment order is strict: no
     * skipping ahead.
     */
    @Transactional
    public LoanInstallment payNextEmi(UUID userId, String reference) {
        Loan loan =
                loans.lockByReferenceAndOwner(reference.trim().toUpperCase(), userId)
                        .orElseThrow(() -> FincoreException.notFound("Loan not found"));
        requireActive(loan);
        List<LoanInstallment> unpaid =
                installments.findByLoanIdAndStatusInOrderByNumberAsc(
                        loan.getId(),
                        EnumSet.of(LoanInstallment.Status.PENDING, LoanInstallment.Status.OVERDUE));
        if (unpaid.isEmpty()) {
            throw FincoreException.conflict("NOTHING_DUE", "All instalments are paid");
        }
        LoanInstallment next = unpaid.get(0);
        Map<UUID, Account> locked = lock(loan.getAccountId());
        BigDecimal amount = next.amountDue();
        Journal journal =
                ledger.post(
                        JournalType.LOAN_REPAYMENT,
                        "EMI "
                                + next.getNumber()
                                + "/"
                                + loan.getTenureMonths()
                                + " of loan "
                                + loan.getReference(),
                        List.of(
                                new Posting(
                                        locked.get(loan.getAccountId()), Direction.DEBIT, amount),
                                new Posting(
                                        locked.get(CashService.SYSTEM_ACCOUNT_ID),
                                        Direction.CREDIT,
                                        amount)));
        next.pay(journal.getId());
        loan.repaid(next.getPrincipalPart(), unpaid.size() == 1);
        return next;
    }

    /**
     * Foreclosure: outstanding principal plus one month's interest, no further penalty; remaining
     * instalments are waived.
     */
    @Transactional
    public Loan foreclose(UUID userId, String reference) {
        Loan loan =
                loans.lockByReferenceAndOwner(reference.trim().toUpperCase(), userId)
                        .orElseThrow(() -> FincoreException.notFound("Loan not found"));
        requireActive(loan);
        List<LoanInstallment> unpaid =
                installments.findByLoanIdAndStatusInOrderByNumberAsc(
                        loan.getId(),
                        EnumSet.of(LoanInstallment.Status.PENDING, LoanInstallment.Status.OVERDUE));
        BigDecimal lateFees =
                unpaid.stream()
                        .map(LoanInstallment::getLateFee)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal interest = unpaid.isEmpty() ? BigDecimal.ZERO : unpaid.get(0).getInterestPart();
        BigDecimal payoff = loan.getOutstandingPrincipal().add(interest).add(lateFees);

        Map<UUID, Account> locked = lock(loan.getAccountId());
        ledger.post(
                JournalType.LOAN_REPAYMENT,
                "Foreclosure of loan " + loan.getReference(),
                List.of(
                        new Posting(locked.get(loan.getAccountId()), Direction.DEBIT, payoff),
                        new Posting(
                                locked.get(CashService.SYSTEM_ACCOUNT_ID),
                                Direction.CREDIT,
                                payoff)));
        unpaid.forEach(LoanInstallment::waive);
        loan.foreclose();
        audit.record(
                userId.toString(),
                "LOAN_FORECLOSED",
                "LOAN",
                loan.getReference(),
                "payoff=" + payoff);
        return loan;
    }

    /** Marks instalments past their due date OVERDUE and charges a one-off 2% late fee. */
    @Transactional
    public int markOverdue() {
        var late =
                installments.findByStatusAndDueDateBefore(
                        LoanInstallment.Status.PENDING, LocalDate.now(props.zone()));
        late.forEach(
                i ->
                        i.markOverdue(
                                i.getEmi()
                                        .multiply(LATE_FEE_RATE)
                                        .setScale(2, RoundingMode.HALF_UP)));
        if (!late.isEmpty()) {
            log.info("Marked {} instalments overdue", late.size());
        }
        return late.size();
    }

    @Transactional(readOnly = true)
    public List<Loan> list(UUID userId) {
        return loans.findByOwnerUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Loan get(UUID userId, String reference) {
        return loans.findByReferenceAndOwnerUserId(reference.trim().toUpperCase(), userId)
                .orElseThrow(() -> FincoreException.notFound("Loan not found"));
    }

    @Transactional(readOnly = true)
    public LoanDetailResponse detail(UUID userId, String reference) {
        Loan loan = get(userId, reference);
        return new LoanDetailResponse(
                LoanResponse.from(loan),
                installments.findByLoanIdOrderByNumberAsc(loan.getId()).stream()
                        .map(InstallmentResponse::from)
                        .toList());
    }

    @Transactional(readOnly = true)
    public List<LoanInstallment> schedule(UUID userId, String reference) {
        Loan loan = get(userId, reference);
        return installments.findByLoanIdOrderByNumberAsc(loan.getId());
    }

    private static void requireActive(Loan loan) {
        if (loan.getStatus() != Loan.Status.ACTIVE) {
            throw FincoreException.conflict("LOAN_NOT_ACTIVE", "This loan is not active");
        }
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

    private static BigDecimal fee(LoanType type, BigDecimal principal) {
        return principal
                .multiply(type.feePercent())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static void validateTerms(LoanType type, BigDecimal principal, int months) {
        if (principal.compareTo(LoanType.MIN_PRINCIPAL) < 0
                || principal.compareTo(type.maxPrincipal()) > 0) {
            throw FincoreException.badRequest(
                    "INVALID_PRINCIPAL",
                    type + " loans range from 10,000 to " + type.maxPrincipal().toPlainString());
        }
        if (months < type.minMonths() || months > type.maxMonths()) {
            throw FincoreException.badRequest(
                    "INVALID_TENURE",
                    type
                            + " loans run "
                            + type.minMonths()
                            + " to "
                            + type.maxMonths()
                            + " months");
        }
    }
}
