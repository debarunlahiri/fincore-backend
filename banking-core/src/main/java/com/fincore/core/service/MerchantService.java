package com.fincore.core.service;

import com.fincore.audit.service.AuditService;
import com.fincore.common.exception.DependencyUnavailableException;
import com.fincore.common.exception.FincoreException;
import com.fincore.core.client.CustomerClient;
import com.fincore.core.config.FincoreProperties;
import com.fincore.core.entity.Account;
import com.fincore.core.entity.Direction;
import com.fincore.core.entity.Journal;
import com.fincore.core.entity.JournalType;
import com.fincore.core.entity.Merchant;
import com.fincore.core.entity.MerchantInvite;
import com.fincore.core.entity.MerchantMember;
import com.fincore.core.entity.MerchantPermission;
import com.fincore.core.entity.MerchantRole;
import com.fincore.core.entity.MerchantTeamInvite;
import com.fincore.core.entity.MerchantPayment;
import com.fincore.core.entity.MerchantPlan;
import com.fincore.core.entity.MerchantSettlement;
import com.fincore.core.model.Posting;
import com.fincore.core.repository.AccountRepository;
import com.fincore.core.repository.MerchantInviteRepository;
import com.fincore.core.repository.MerchantMemberRepository;
import com.fincore.core.repository.MerchantTeamInviteRepository;
import com.fincore.core.repository.MerchantPaymentRepository;
import com.fincore.core.repository.MerchantRepository;
import com.fincore.core.repository.MerchantSettlementRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Merchant accounts. A customer registers as a merchant (STARTER plan: no fee, tight limits) and
 * other customers pay the merchant code. The customer is debited at once and the money is held on
 * the system account until a settlement pays it out, minus the plan fee. A BUSINESS merchant is a
 * "main merchant" that can invite franchise outlets: an outlet is a merchant of its own that takes
 * its plan, fee and limits from the main merchant and passes an agreed royalty to it at every
 * settlement.
 *
 * <p>Every ledger movement locks the accounts involved in one {@code lockAllOrdered} call, so
 * payments, refunds and settlements on the same merchant are serialised and limits cannot be
 * overshot by concurrent requests.
 */
@Service
public class MerchantService {

    private static final Logger log = LoggerFactory.getLogger(MerchantService.class);

    public static final String MANUAL = "MANUAL";
    public static final String SCHEDULED = "SCHEDULED";
    private static final long INVITE_VALID_DAYS = 7;
    private static final int MAX_BACKOFF_HOURS = 24;

    /** Errors that another of the customer's own accounts could fix. Merchant-side errors never fall back. */
    private static final Set<String> BACKUP_ACCOUNT_CODES =
            Set.of("INSUFFICIENT_FUNDS", "ACCOUNT_NOT_ACTIVE", "DAILY_LIMIT_EXCEEDED");

    public record Overview(
            Merchant merchant,
            Account account,
            MerchantPlan plan,
            Merchant parent,
            long outletCount,
            BigDecimal todayVolume,
            long todayCount,
            BigDecimal monthVolume,
            BigDecimal pendingNet,
            long pendingCount,
            MerchantRole role) {}

    /** Who the signed-in customer is within a merchant: the owner, or a team member with a role. */
    public record Access(Merchant merchant, MerchantRole role, MerchantMember member) {}

    public record MemberView(MerchantMember member, boolean you) {}

    public record TeamView(
            int maxMembers, List<MemberView> members, List<MerchantTeamInvite> invites) {}

    public record TeamInvitePreview(MerchantTeamInvite invite, Merchant merchant) {}

    public record Qr(
            Merchant merchant, BigDecimal amount, String note, String payload, String svg) {}

    public record Receipt(
            MerchantPayment payment,
            String merchantCode,
            String businessName,
            String paidFromAccount,
            boolean usedBackup) {}

    public record InvitePreview(MerchantInvite invite, Merchant parent) {}

    public record OutletView(
            Merchant outlet,
            BigDecimal todayVolume,
            BigDecimal monthVolume,
            BigDecimal royaltyEarned) {}

    public record Network(
            BigDecimal totalRoyalty,
            int maxOutlets,
            List<OutletView> outlets,
            List<MerchantInvite> invites) {}

    private final MerchantRepository merchants;
    private final MerchantPaymentRepository payments;
    private final MerchantSettlementRepository settlements;
    private final MerchantInviteRepository invites;
    private final MerchantMemberRepository members;
    private final MerchantTeamInviteRepository teamInvites;
    private final AccountService accountService;
    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final AuditService audit;
    private final FincoreProperties props;
    private final TransactionTemplate tx;
    private final CustomerClient customers;
    private final QrCodeService qr;
    private final QrSigner qrSigner;
    private final String webBaseUrl;

    public MerchantService(
            MerchantRepository merchants,
            MerchantPaymentRepository payments,
            MerchantSettlementRepository settlements,
            MerchantInviteRepository invites,
            MerchantMemberRepository members,
            MerchantTeamInviteRepository teamInvites,
            AccountService accountService,
            AccountRepository accounts,
            LedgerService ledger,
            AuditService audit,
            FincoreProperties props,
            TransactionTemplate tx,
            CustomerClient customers,
            QrCodeService qr,
            QrSigner qrSigner,
            @org.springframework.beans.factory.annotation.Value("${fincore.web.base-url:http://localhost:3000}")
                    String webBaseUrl) {
        this.qr = qr;
        this.qrSigner = qrSigner;
        this.webBaseUrl = webBaseUrl;
        this.merchants = merchants;
        this.payments = payments;
        this.settlements = settlements;
        this.invites = invites;
        this.members = members;
        this.teamInvites = teamInvites;
        this.accountService = accountService;
        this.accounts = accounts;
        this.ledger = ledger;
        this.audit = audit;
        this.props = props;
        this.tx = tx;
        this.customers = customers;
    }

    // ---- registration -------------------------------------------------------------------------

    /** KYC is a remote call, so it runs before the transaction that opens the account. */
    public Merchant register(UUID userId, String businessName, String category) {
        requireNoMerchant(userId);
        requireKyc(userId);
        return tx.execute(
                status -> {
                    Account account = accountService.createMerchantAccount(userId);
                    Merchant m =
                            merchants.save(
                                    new Merchant(
                                            userId,
                                            account.getId(),
                                            account.getAccountNumber(),
                                            businessName.trim(),
                                            category));
                    audit.record(
                            userId.toString(),
                            "MERCHANT_REGISTERED",
                            "MERCHANT",
                            m.getReference(),
                            null);
                    return m;
                });
    }

    /** A customer joins a franchise with the invite code a main merchant gave them. */
    public Merchant join(UUID userId, String inviteCode, String businessName) {
        requireNoMerchant(userId);
        requireKyc(userId);
        String code = inviteCode.trim().toUpperCase();
        return tx.execute(
                status -> {
                    MerchantInvite invite =
                            invites.lockByReference(code)
                                    .filter(MerchantInvite::isUsable)
                                    .orElseThrow(
                                            () ->
                                                    FincoreException.notFound(
                                                            "Invite not found or expired"));
                    Merchant parent = merchants.findById(invite.getParentMerchantId()).orElseThrow();
                    if (!parent.isActive()) {
                        throw FincoreException.unprocessable(
                                "MERCHANT_UNAVAILABLE", "This franchise is not accepting outlets");
                    }
                    if (merchants.countByParentMerchantId(parent.getId())
                            >= parent.getPlan().maxSubMerchants()) {
                        throw FincoreException.unprocessable(
                                "OUTLET_LIMIT", "This franchise has no room for more outlets");
                    }
                    Account account = accountService.createMerchantAccount(userId);
                    Merchant outlet =
                            merchants.save(
                                    Merchant.outlet(
                                            parent,
                                            userId,
                                            account.getId(),
                                            account.getAccountNumber(),
                                            businessName.trim(),
                                            invite.getRoyaltyPercent()));
                    invite.accept();
                    audit.record(
                            userId.toString(),
                            "MERCHANT_OUTLET_JOINED",
                            "MERCHANT",
                            outlet.getReference(),
                            parent.getReference());
                    return outlet;
                });
    }

    private void requireNoMerchant(UUID userId) {
        if (merchants.existsByOwnerUserId(userId) || members.existsByUserId(userId)) {
            throw FincoreException.conflict(
                    "MERCHANT_EXISTS", "You already belong to a merchant account");
        }
    }

    private void requireKyc(UUID userId) {
        boolean verified;
        try {
            verified = customers.byUser(userId).isKycVerified();
        } catch (DependencyUnavailableException e) {
            // Only registration needs the customer-service. Payments, refunds and settlements of
            // existing merchants never call it, so they keep working while it is down.
            throw new FincoreException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "KYC_UNAVAILABLE",
                    "Identity verification is temporarily unavailable. Existing merchant accounts keep working; please try again shortly.");
        }
        if (!verified) {
            throw FincoreException.unprocessable(
                    "KYC_REQUIRED", "KYC must be verified before registering as a merchant");
        }
    }

    // ---- reading ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Access access(UUID userId) {
        var owned = merchants.findByOwnerUserId(userId);
        if (owned.isPresent()) {
            return new Access(owned.get(), MerchantRole.OWNER, null);
        }
        MerchantMember member =
                members.findByUserId(userId)
                        .orElseThrow(
                                () ->
                                        FincoreException.notFound(
                                                "You do not have a merchant account yet"));
        return new Access(
                merchants.findById(member.getMerchantId()).orElseThrow(), member.getRole(), member);
    }

    /** The merchant the customer works for, if their role grants the permission. */
    private Merchant require(UUID userId, MerchantPermission permission) {
        Access a = access(userId);
        if (!a.role().can(permission)) {
            throw new FincoreException(
                    HttpStatus.FORBIDDEN,
                    "MERCHANT_PERMISSION_DENIED",
                    "Your role (" + a.role().displayName() + ") does not allow this");
        }
        return a.merchant();
    }

    private MerchantPlan planOf(Merchant m) {
        return m.isSubMerchant()
                ? merchants.findById(m.getParentMerchantId()).orElseThrow().getPlan()
                : m.getPlan();
    }

    @Transactional(readOnly = true)
    public Overview overview(UUID userId) {
        Access access = access(userId);
        Merchant m = access.merchant();
        Merchant parent =
                m.isSubMerchant() ? merchants.findById(m.getParentMerchantId()).orElseThrow() : null;
        MerchantPlan plan = parent == null ? m.getPlan() : parent.getPlan();
        Account account = accounts.findById(m.getAccountId()).orElseThrow();
        return new Overview(
                m,
                account,
                plan,
                parent,
                merchants.countByParentMerchantId(m.getId()),
                payments.volumeSince(m.getId(), startOfDay()),
                payments.countSince(m.getId(), startOfDay()),
                payments.volumeSince(m.getId(), startOfMonth()),
                payments.pendingNet(m.getId()),
                payments.pendingCount(m.getId()),
                access.role());
    }

    @Transactional(readOnly = true)
    public Merchant lookup(String merchantCode) {
        return merchants.findByReference(merchantCode.trim().toUpperCase())
                .filter(Merchant::isActive)
                .orElseThrow(() -> FincoreException.notFound("Merchant not found"));
    }

    @Transactional(readOnly = true)
    public List<MerchantPayment> payments(UUID userId) {
        Merchant m = require(userId, MerchantPermission.VIEW_PAYMENTS);
        return payments.findByMerchantIdOrderByCreatedAtDesc(m.getId(), PageRequest.of(0, 100));
    }

    @Transactional(readOnly = true)
    public List<MerchantSettlement> settlements(UUID userId) {
        Merchant m = require(userId, MerchantPermission.VIEW_SETTLEMENTS);
        return settlements.findByMerchantIdOrderByCreatedAtDesc(m.getId(), PageRequest.of(0, 50));
    }

    /**
     * The merchant's payment QR. Without an amount it is the standing counter QR (the customer types
     * the amount); with one it is a fixed-amount QR for a bill. Any role that can see payments may
     * show it. The QR only carries the merchant code, amount and note, never an internal id.
     */
    @Transactional(readOnly = true)
    public Qr qr(UUID userId, BigDecimal amount, String note) {
        Merchant m = require(userId, MerchantPermission.VIEW_PAYMENTS);
        if (!m.isActive()) {
            throw FincoreException.unprocessable(
                    "MERCHANT_UNAVAILABLE", "This merchant is not accepting payments");
        }
        if (amount != null) {
            if (amount.compareTo(new BigDecimal("1.00")) < 0 || amount.scale() > 2) {
                throw FincoreException.badRequest(
                        "INVALID_AMOUNT", "The amount must be at least 1.00, with at most 2 decimals");
            }
            if (amount.compareTo(planOf(m).perTransactionLimit()) > 0) {
                throw FincoreException.unprocessable(
                        "MERCHANT_LIMIT_REACHED",
                        "The amount is above your plan's per-payment limit");
            }
        }
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        var uri =
                org.springframework.web.util.UriComponentsBuilder.fromUriString(
                                webBaseUrl.replaceAll("/+$", "") + "/pay-merchant")
                        .queryParam("m", m.getReference());
        if (amount != null) {
            uri.queryParam("amt", amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
            // signed, so the payer cannot edit the amount in the link
            uri.queryParam("sig", qrSigner.sign(m.getReference(), amount));
        }
        if (cleanNote != null) {
            uri.queryParam("note", cleanNote.length() > 60 ? cleanNote.substring(0, 60) : cleanNote);
        }
        String payload = uri.encode().build().toUriString();
        return new Qr(m, amount, cleanNote, payload, qr.svg(payload));
    }

    // ---- plan ---------------------------------------------------------------------------------

    @Transactional
    public Merchant changePlan(UUID userId, MerchantPlan plan) {
        Merchant m = require(userId, MerchantPermission.MANAGE_PLAN);
        if (m.isSubMerchant()) {
            throw FincoreException.conflict(
                    "PLAN_MANAGED_BY_MAIN",
                    "Franchise outlets use the plan of their main merchant");
        }
        if (m.getPlan() == plan) {
            throw FincoreException.conflict("SAME_PLAN", "You are already on this plan");
        }
        if (merchants.countByParentMerchantId(m.getId()) > plan.maxSubMerchants()) {
            throw FincoreException.conflict(
                    "HAS_OUTLETS",
                    "Your franchise network is larger than the "
                            + plan.displayName()
                            + " plan allows");
        }
        MerchantPlan old = m.getPlan();
        m.changePlan(plan);
        audit.record(
                userId.toString(),
                "MERCHANT_PLAN_CHANGED",
                "MERCHANT",
                m.getReference(),
                old + "->" + plan);
        return m;
    }

    // ---- payments -----------------------------------------------------------------------------

    /**
     * Customer pays a merchant. The merchant's limits are checked under the merchant account lock.
     * Customers only ever get a generic message when a merchant limit is hit.
     */
    public Receipt pay(
            UUID userId,
            String merchantCode,
            String accountNumber,
            BigDecimal amount,
            String note,
            List<String> backupAccountNumbers,
            String sig) {
        Merchant target = lookup(merchantCode);
        assertLinkAmount(merchantCode, amount, sig);
        // chosen account first, then the backups in order; all must belong to the customer
        List<UUID> accountIds = new ArrayList<>();
        accountIds.add(accountService.ownedAccountId(userId, accountNumber));
        if (backupAccountNumbers != null) {
            for (String number : backupAccountNumbers) {
                UUID id = accountService.ownedAccountId(userId, number);
                if (!accountIds.contains(id)) {
                    accountIds.add(id);
                }
            }
        }
        FincoreException firstFailure = null;
        for (int i = 0; i < accountIds.size(); i++) {
            UUID payerAccountId = accountIds.get(i);
            try {
                // one transaction per attempt: a failed attempt leaves nothing behind
                Receipt r =
                        tx.execute(
                                status ->
                                        doPay(userId, target.getId(), payerAccountId, amount, note));
                return new Receipt(
                        r.payment(), r.merchantCode(), r.businessName(), r.paidFromAccount(), i > 0);
            } catch (FincoreException e) {
                if (!BACKUP_ACCOUNT_CODES.contains(e.code())) {
                    throw e;
                }
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        throw firstFailure;
    }

    /** A payment link that carries a signature fixes the amount: any other amount is refused. */
    public void assertLinkAmount(String merchantCode, BigDecimal amount, String sig) {
        if (sig != null && !sig.isBlank() && !qrSigner.verify(merchantCode, amount, sig)) {
            throw FincoreException.badRequest(
                    "AMOUNT_MISMATCH", "The amount does not match this payment link");
        }
    }

    /** Whether the merchant's plan limits allow a payment of this size right now. */
    @Transactional(readOnly = true)
    public void assertCanAccept(Merchant merchant, BigDecimal amount) {
        assertMerchantLimits(merchant, planOf(merchant), amount);
    }

    /**
     * Books money that already arrived through the payment gateway: gateway account to the system
     * hold, plus a merchant payment with no customer behind it. Runs in the caller's transaction.
     * Limits were checked when the order was created; the money is taken, so they are not
     * rechecked here.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public MerchantPayment recordGuestPayment(
            UUID merchantId, BigDecimal amount, String note, String gatewayReference) {
        Merchant merchant = merchants.findById(merchantId).orElseThrow();
        Merchant parent =
                merchant.isSubMerchant()
                        ? merchants.findById(merchant.getParentMerchantId()).orElseThrow()
                        : null;
        MerchantPlan plan = parent == null ? merchant.getPlan() : parent.getPlan();
        Map<UUID, Account> locked =
                accounts
                        .lockAllOrdered(
                                List.of(
                                        CashService.GATEWAY_ACCOUNT_ID,
                                        CashService.SYSTEM_ACCOUNT_ID,
                                        merchant.getAccountId()))
                        .stream()
                        .collect(Collectors.toMap(Account::getId, Function.identity()));
        Account gateway = locked.get(CashService.GATEWAY_ACCOUNT_ID);
        Account system = locked.get(CashService.SYSTEM_ACCOUNT_ID);
        if (gateway == null || system == null) {
            throw FincoreException.notFound("Account not found");
        }
        BigDecimal fee = plan.feeFor(amount);
        BigDecimal royalty =
                parent == null
                        ? BigDecimal.ZERO.setScale(2)
                        : amount.subtract(fee)
                                .multiply(merchant.getRoyaltyPercent())
                                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        Journal journal =
                ledger.post(
                        JournalType.MERCHANT_PAYMENT,
                        "Guest payment to " + merchant.getBusinessName(),
                        List.of(
                                new Posting(gateway, Direction.DEBIT, amount),
                                new Posting(system, Direction.CREDIT, amount)));
        MerchantPayment payment =
                payments.save(
                        MerchantPayment.guest(
                                merchant.getId(),
                                amount,
                                fee,
                                royalty,
                                note == null || note.isBlank() ? null : note.trim(),
                                journal.getReference()));
        audit.record(
                "guest", "MERCHANT_PAYMENT", "MERCHANT_PAYMENT", payment.getReference(), gatewayReference);
        return payment;
    }

    private Receipt doPay(
            UUID userId, UUID merchantId, UUID payerAccountId, BigDecimal amount, String note) {
        Merchant merchant = merchants.findById(merchantId).orElseThrow();
        Merchant parent =
                merchant.isSubMerchant()
                        ? merchants.findById(merchant.getParentMerchantId()).orElseThrow()
                        : null;
        if (!merchant.isActive() || (parent != null && !parent.isActive())) {
            throw FincoreException.unprocessable(
                    "MERCHANT_UNAVAILABLE", "This merchant is not accepting payments");
        }
        if (merchant.getOwnerUserId().equals(userId)
                || members.existsByMerchantIdAndUserId(merchant.getId(), userId)) {
            throw FincoreException.badRequest(
                    "OWN_MERCHANT", "You cannot pay a merchant account you work for");
        }
        MerchantPlan plan = parent == null ? merchant.getPlan() : parent.getPlan();

        Map<UUID, Account> locked =
                accounts
                        .lockAllOrdered(
                                List.of(
                                        payerAccountId,
                                        CashService.SYSTEM_ACCOUNT_ID,
                                        merchant.getAccountId()))
                        .stream()
                        .collect(Collectors.toMap(Account::getId, Function.identity()));
        Account payer = locked.get(payerAccountId);
        Account system = locked.get(CashService.SYSTEM_ACCOUNT_ID);
        if (payer == null || system == null || locked.get(merchant.getAccountId()) == null) {
            throw FincoreException.notFound("Account not found");
        }

        assertMerchantLimits(merchant, plan, amount);
        Instant dayStart = startOfDay();
        if (payments.paidByAccountSince(payerAccountId, dayStart).add(amount)
                        .compareTo(payer.getDailyTransferLimit())
                > 0) {
            throw FincoreException.unprocessable(
                    "DAILY_LIMIT_EXCEEDED", "Daily payment limit of your account exceeded");
        }

        BigDecimal fee = plan.feeFor(amount);
        BigDecimal royalty =
                parent == null
                        ? BigDecimal.ZERO.setScale(2)
                        : amount.subtract(fee)
                                .multiply(merchant.getRoyaltyPercent())
                                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

        Journal journal =
                ledger.post(
                        JournalType.MERCHANT_PAYMENT,
                        "Payment to " + merchant.getBusinessName(),
                        List.of(
                                new Posting(payer, Direction.DEBIT, amount),
                                new Posting(system, Direction.CREDIT, amount)));
        MerchantPayment payment =
                payments.save(
                        new MerchantPayment(
                                merchant.getId(),
                                userId,
                                payerAccountId,
                                mask(payer.getAccountNumber()),
                                amount,
                                fee,
                                royalty,
                                note == null || note.isBlank() ? null : note.trim(),
                                journal.getReference()));
        audit.record(
                userId.toString(),
                "MERCHANT_PAYMENT",
                "MERCHANT_PAYMENT",
                payment.getReference(),
                merchant.getReference());
        return new Receipt(
                payment,
                merchant.getReference(),
                merchant.getBusinessName(),
                payer.getAccountNumber(),
                false);
    }

    private void assertMerchantLimits(Merchant merchant, MerchantPlan plan, BigDecimal amount) {
        Instant dayStart = startOfDay();
        boolean over =
                amount.compareTo(plan.perTransactionLimit()) > 0
                        || payments.volumeSince(merchant.getId(), dayStart).add(amount)
                                        .compareTo(plan.dailyVolumeLimit())
                                > 0
                        || payments.countSince(merchant.getId(), dayStart) + 1
                                > plan.dailyTransactionLimit()
                        || payments.volumeSince(merchant.getId(), startOfMonth()).add(amount)
                                        .compareTo(plan.monthlyVolumeLimit())
                                > 0;
        if (over) {
            throw FincoreException.unprocessable(
                    "MERCHANT_LIMIT_REACHED",
                    "This merchant cannot accept a payment of this size right now");
        }
    }

    /**
     * Full refund of a payment that has not been settled yet: the held money goes back to the
     * customer and the merchant is never credited. Settled payments cannot be refunded here.
     */
    public MerchantPayment refund(UUID userId, String paymentReference) {
        Merchant m = require(userId, MerchantPermission.REFUND);
        String ref = paymentReference.trim().toUpperCase();
        UUID payerAccountId =
                payments.findPayerAccountId(ref, m.getId())
                        .orElseThrow(() -> FincoreException.notFound("Payment not found"));
        return tx.execute(
                status -> {
                    // lock first, load the payment after: a settlement that committed meanwhile
                    // is then visible
                    Map<UUID, Account> locked =
                            accounts
                                    .lockAllOrdered(
                                            List.of(
                                                    payerAccountId,
                                                    CashService.SYSTEM_ACCOUNT_ID,
                                                    m.getAccountId()))
                                    .stream()
                                    .collect(
                                            Collectors.toMap(
                                                    Account::getId, Function.identity()));
                    MerchantPayment p =
                            payments.findByReferenceAndMerchantId(ref, m.getId()).orElseThrow();
                    if (p.isRefunded()) {
                        throw FincoreException.conflict(
                                "ALREADY_REFUNDED", "This payment was already refunded");
                    }
                    if (p.isSettled()) {
                        throw FincoreException.unprocessable(
                                "ALREADY_SETTLED",
                                "This payment has been settled and can no longer be refunded here");
                    }
                    Journal journal;
                    try {
                        journal =
                                ledger.post(
                                        JournalType.MERCHANT_REFUND,
                                        "Refund from " + m.getBusinessName(),
                                        List.of(
                                                new Posting(
                                                        locked.get(CashService.SYSTEM_ACCOUNT_ID),
                                                        Direction.DEBIT,
                                                        p.getAmount()),
                                                new Posting(
                                                        locked.get(payerAccountId),
                                                        Direction.CREDIT,
                                                        p.getAmount())));
                    } catch (FincoreException e) {
                        if ("ACCOUNT_NOT_ACTIVE".equals(e.code())) {
                            // the message would reveal the customer's account number
                            throw FincoreException.unprocessable(
                                    "REFUND_UNAVAILABLE",
                                    "The customer's account cannot receive the refund right now");
                        }
                        throw e;
                    }
                    p.markRefunded(journal.getReference());
                    audit.record(
                            userId.toString(),
                            "MERCHANT_REFUND",
                            "MERCHANT_PAYMENT",
                            p.getReference(),
                            null);
                    return p;
                });
    }

    // ---- settlement ---------------------------------------------------------------------------

    /** The merchant asks for a payout of everything that is already eligible. */
    public MerchantSettlement settleNow(UUID userId) {
        Merchant m = require(userId, MerchantPermission.SETTLE);
        MerchantSettlement s;
        try {
            s = settle(m.getId(), MANUAL);
        } catch (RuntimeException e) {
            recordSettlementFailure(m.getId(), e);
            throw e;
        }
        if (s == null) {
            throw FincoreException.unprocessable(
                    "NOTHING_TO_SETTLE", "No payments are ready to settle yet");
        }
        return s;
    }

    /** Back office: settle one merchant now. Same rules as the merchant's own "settle now". */
    public MerchantSettlement settleByCode(String merchantCode, String actor) {
        Merchant m =
                merchants
                        .findByReference(merchantCode.trim().toUpperCase())
                        .orElseThrow(() -> FincoreException.notFound("Merchant not found"));
        MerchantSettlement s;
        try {
            s = settle(m.getId(), MANUAL);
        } catch (RuntimeException e) {
            recordSettlementFailure(m.getId(), e);
            throw e;
        }
        if (s == null) {
            throw FincoreException.unprocessable(
                    "NOTHING_TO_SETTLE", "No payments are ready to settle yet");
        }
        audit.record(actor, "MERCHANT_SETTLED_BY_STAFF", "MERCHANT", m.getReference(), s.getReference());
        return s;
    }

    /** Daily job: settles every merchant whose payments have become eligible. */
    public int settleAllDue() {
        int done = 0;
        for (UUID id : merchants.idsWithUnsettledPaymentsBefore(Instant.now())) {
            try {
                if (settle(id, SCHEDULED) != null) {
                    done++;
                }
            } catch (RuntimeException e) {
                log.warn("Settlement for merchant {} failed: {}", id, e.getMessage());
                recordSettlementFailure(id, e);
            }
        }
        return done;
    }

    /**
     * Pays out the eligible payments of one merchant and returns the settlement, or null when
     * nothing is due. Debits the system account by gross minus fee (the fee stays there as the
     * platform's revenue), credits the merchant with the net and the main merchant with the
     * royalty.
     */
    MerchantSettlement settle(UUID merchantId, String trigger) {
        return tx.execute(
                status -> {
                    Merchant m = merchants.findById(merchantId).orElseThrow();
                    Merchant parent =
                            m.isSubMerchant()
                                    ? merchants.findById(m.getParentMerchantId()).orElseThrow()
                                    : null;
                    MerchantPlan plan = parent == null ? m.getPlan() : parent.getPlan();
                    Instant cutoff =
                            Instant.now().minus(plan.settlementDelayDays(), ChronoUnit.DAYS);

                    List<UUID> ids = new ArrayList<>();
                    ids.add(CashService.SYSTEM_ACCOUNT_ID);
                    ids.add(m.getAccountId());
                    if (parent != null) {
                        ids.add(parent.getAccountId());
                    }
                    Map<UUID, Account> locked =
                            accounts.lockAllOrdered(ids).stream()
                                    .collect(
                                            Collectors.toMap(
                                                    Account::getId, Function.identity()));

                    List<MerchantPayment> due = payments.settleable(m.getId(), cutoff);
                    if (due.isEmpty()) {
                        return null;
                    }
                    BigDecimal gross = BigDecimal.ZERO;
                    BigDecimal fee = BigDecimal.ZERO;
                    BigDecimal royaltyDue = BigDecimal.ZERO;
                    BigDecimal net = BigDecimal.ZERO;
                    for (MerchantPayment p : due) {
                        gross = gross.add(p.getAmount());
                        fee = fee.add(p.getFee());
                        royaltyDue = royaltyDue.add(p.getRoyalty());
                        net = net.add(p.getNetAmount());
                    }

                    // Royalty fallback: if the main merchant cannot receive money right now
                    // (account frozen), the outlet is still paid its net. The royalty stays on the
                    // system account and is paid, with whatever else is owed, once the main
                    // merchant can receive again.
                    BigDecimal royalty = BigDecimal.ZERO.setScale(2);
                    BigDecimal royaltyHeld = BigDecimal.ZERO.setScale(2);
                    if (parent != null) {
                        BigDecimal owed = royaltyDue.add(m.getRoyaltyOwed());
                        if (owed.signum() > 0) {
                            if (locked.get(parent.getAccountId()).isActive()) {
                                royalty = owed;
                                m.setRoyaltyOwed(BigDecimal.ZERO.setScale(2));
                            } else {
                                royaltyHeld = royaltyDue;
                                m.setRoyaltyOwed(owed);
                            }
                        }
                    }

                    List<Posting> postings = new ArrayList<>();
                    postings.add(
                            new Posting(
                                    locked.get(CashService.SYSTEM_ACCOUNT_ID),
                                    Direction.DEBIT,
                                    net.add(royalty)));
                    postings.add(new Posting(locked.get(m.getAccountId()), Direction.CREDIT, net));
                    if (royalty.signum() > 0) {
                        postings.add(
                                new Posting(
                                        locked.get(parent.getAccountId()),
                                        Direction.CREDIT,
                                        royalty));
                    }
                    Journal journal =
                            ledger.post(
                                    JournalType.MERCHANT_SETTLEMENT,
                                    "Settlement for " + m.getBusinessName(),
                                    postings);

                    MerchantSettlement settlement =
                            settlements.save(
                                    new MerchantSettlement(
                                            m.getId(),
                                            royalty.signum() > 0 ? parent.getId() : null,
                                            due.size(),
                                            gross,
                                            fee,
                                            royalty,
                                            royaltyHeld,
                                            net,
                                            trigger,
                                            journal.getReference()));
                    due.forEach(p -> p.markSettled(settlement.getId()));
                    m.clearSettlementFailure();
                    audit.record(
                            m.getOwnerUserId().toString(),
                            "MERCHANT_SETTLED",
                            "MERCHANT_SETTLEMENT",
                            settlement.getReference(),
                            trigger);
                    return settlement;
                });
    }

    /**
     * Settlement fallback: a failed settlement leaves the payments pending and puts the merchant
     * into retry back-off (2h, 4h, 8h, 16h, then daily), so one broken account cannot make the job
     * fail over and over. The reason is shown on the merchant's dashboard; a manual "settle now"
     * ignores the back-off. Stored in its own transaction because the settlement's rolled back.
     */
    private void recordSettlementFailure(UUID merchantId, RuntimeException cause) {
        String reason =
                cause instanceof FincoreException fe && "ACCOUNT_NOT_ACTIVE".equals(fe.code())
                        ? "A merchant account involved in the settlement is frozen or closed"
                        : "The settlement could not be completed and will be retried automatically";
        try {
            tx.executeWithoutResult(
                    status ->
                            merchants
                                    .findById(merchantId)
                                    .ifPresent(
                                            m -> {
                                                long hours =
                                                        Math.min(
                                                                1L
                                                                        << Math.min(
                                                                                m.getSettlementFailures()
                                                                                        + 1,
                                                                                5),
                                                                MAX_BACKOFF_HOURS);
                                                m.recordSettlementFailure(
                                                        reason,
                                                        Instant.now()
                                                                .plus(hours, ChronoUnit.HOURS));
                                            }));
        } catch (RuntimeException e) {
            log.warn("Could not record settlement failure for {}: {}", merchantId, e.getMessage());
        }
    }

    // ---- franchise network --------------------------------------------------------------------

    @Transactional
    public MerchantInvite createInvite(UUID userId, BigDecimal royaltyPercent, String note) {
        Merchant m = requireMain(userId, MerchantPermission.MANAGE_NETWORK);
        int max = m.getPlan().maxSubMerchants();
        if (max == 0) {
            throw FincoreException.unprocessable(
                    "PLAN_UPGRADE_REQUIRED",
                    "Upgrade to the Business plan to add franchise outlets");
        }
        long taken =
                merchants.countByParentMerchantId(m.getId())
                        + invites.countByParentMerchantIdAndStatus(
                                m.getId(), MerchantInvite.PENDING);
        if (taken >= max) {
            throw FincoreException.unprocessable(
                    "OUTLET_LIMIT", "Your plan allows at most " + max + " outlets");
        }
        MerchantInvite invite =
                invites.save(
                        new MerchantInvite(
                                m.getId(),
                                royaltyPercent.setScale(2, RoundingMode.HALF_UP),
                                note == null || note.isBlank() ? null : note.trim(),
                                Instant.now().plus(INVITE_VALID_DAYS, ChronoUnit.DAYS)));
        audit.record(
                userId.toString(), "MERCHANT_INVITE_CREATED", "MERCHANT_INVITE",
                invite.getReference(), null);
        return invite;
    }

    @Transactional
    public void cancelInvite(UUID userId, String inviteCode) {
        Merchant m = requireMain(userId, MerchantPermission.MANAGE_NETWORK);
        MerchantInvite invite =
                invites.findByReferenceAndParentMerchantId(inviteCode.trim().toUpperCase(), m.getId())
                        .orElseThrow(() -> FincoreException.notFound("Invite not found"));
        if (!MerchantInvite.PENDING.equals(invite.getStatus())) {
            throw FincoreException.conflict("INVITE_CLOSED", "This invite can no longer be cancelled");
        }
        invite.cancel();
    }

    /** Terms shown to a prospective franchisee; 404 for unknown, used or expired codes. */
    @Transactional(readOnly = true)
    public InvitePreview previewInvite(String inviteCode) {
        MerchantInvite invite =
                invites.findByReference(inviteCode.trim().toUpperCase())
                        .filter(MerchantInvite::isUsable)
                        .orElseThrow(
                                () -> FincoreException.notFound("Invite not found or expired"));
        return new InvitePreview(
                invite, merchants.findById(invite.getParentMerchantId()).orElseThrow());
    }

    @Transactional(readOnly = true)
    public Network network(UUID userId) {
        Merchant m = requireMain(userId, MerchantPermission.VIEW_NETWORK);
        Instant day = startOfDay();
        Instant month = startOfMonth();
        List<OutletView> outlets =
                merchants.findByParentMerchantIdOrderByCreatedAtAsc(m.getId()).stream()
                        .map(
                                o ->
                                        new OutletView(
                                                o,
                                                payments.volumeSince(o.getId(), day),
                                                payments.volumeSince(o.getId(), month),
                                                settlements.royaltyFromOutlet(
                                                        o.getId(), m.getId())))
                        .toList();
        return new Network(
                settlements.totalRoyalty(m.getId()),
                m.getPlan().maxSubMerchants(),
                outlets,
                invites.findByParentMerchantIdOrderByCreatedAtDesc(m.getId()));
    }

    @Transactional
    public Merchant setOutletSuspended(UUID userId, String outletCode, boolean suspended) {
        Merchant m = requireMain(userId, MerchantPermission.MANAGE_NETWORK);
        Merchant outlet =
                merchants.findByReferenceAndParentMerchantId(
                                outletCode.trim().toUpperCase(), m.getId())
                        .orElseThrow(() -> FincoreException.notFound("Outlet not found"));
        if (suspended) {
            outlet.suspend();
        } else {
            outlet.resume();
        }
        audit.record(
                userId.toString(),
                suspended ? "MERCHANT_OUTLET_SUSPENDED" : "MERCHANT_OUTLET_RESUMED",
                "MERCHANT",
                outlet.getReference(),
                null);
        return outlet;
    }

    private Merchant requireMain(UUID userId, MerchantPermission permission) {
        Merchant m = require(userId, permission);
        if (m.isSubMerchant()) {
            throw FincoreException.unprocessable(
                    "NOT_A_MAIN_MERCHANT", "Only a main merchant can manage a franchise network");
        }
        return m;
    }


    // ---- team (sub-roles inside a merchant) ---------------------------------------------------

    @Transactional(readOnly = true)
    public TeamView team(UUID userId) {
        Merchant m = require(userId, MerchantPermission.MANAGE_TEAM);
        return new TeamView(
                planOf(m).maxTeamMembers(),
                members.findByMerchantIdOrderByCreatedAtAsc(m.getId()).stream()
                        .map(x -> new MemberView(x, false))
                        .toList(),
                teamInvites.findByMerchantIdOrderByCreatedAtDesc(m.getId()));
    }

    @Transactional
    public MerchantTeamInvite createTeamInvite(UUID userId, MerchantRole role, String label) {
        Merchant m = require(userId, MerchantPermission.MANAGE_TEAM);
        if (role == MerchantRole.OWNER) {
            throw FincoreException.badRequest("INVALID_ROLE", "A team can have only one owner");
        }
        int max = planOf(m).maxTeamMembers();
        long taken =
                members.countByMerchantId(m.getId())
                        + teamInvites.countByMerchantIdAndStatus(
                                m.getId(), MerchantTeamInvite.PENDING);
        if (taken >= max) {
            throw FincoreException.unprocessable(
                    "TEAM_LIMIT", "Your plan allows at most " + max + " team members");
        }
        MerchantTeamInvite invite =
                teamInvites.save(
                        new MerchantTeamInvite(
                                m.getId(),
                                role,
                                label.trim(),
                                Instant.now().plus(INVITE_VALID_DAYS, ChronoUnit.DAYS)));
        audit.record(
                userId.toString(), "MERCHANT_TEAM_INVITE", "MERCHANT", m.getReference(), role.name());
        return invite;
    }

    @Transactional
    public void cancelTeamInvite(UUID userId, String inviteCode) {
        Merchant m = require(userId, MerchantPermission.MANAGE_TEAM);
        MerchantTeamInvite invite =
                teamInvites
                        .findByReferenceAndMerchantId(inviteCode.trim().toUpperCase(), m.getId())
                        .orElseThrow(() -> FincoreException.notFound("Invite not found"));
        if (!MerchantTeamInvite.PENDING.equals(invite.getStatus())) {
            throw FincoreException.conflict(
                    "INVITE_CLOSED", "This invite can no longer be cancelled");
        }
        invite.cancel();
    }

    @Transactional
    public MerchantMember changeMemberRole(UUID userId, String memberCode, MerchantRole role) {
        Merchant m = require(userId, MerchantPermission.MANAGE_TEAM);
        if (role == MerchantRole.OWNER) {
            throw FincoreException.badRequest("INVALID_ROLE", "A team can have only one owner");
        }
        MerchantMember member = memberOf(m, memberCode);
        member.changeRole(role);
        audit.record(
                userId.toString(), "MERCHANT_MEMBER_ROLE", "MERCHANT", m.getReference(), role.name());
        return member;
    }

    @Transactional
    public void removeMember(UUID userId, String memberCode) {
        Merchant m = require(userId, MerchantPermission.MANAGE_TEAM);
        members.delete(memberOf(m, memberCode));
        audit.record(userId.toString(), "MERCHANT_MEMBER_REMOVED", "MERCHANT", m.getReference(), memberCode);
    }

    /** A member leaves the team. The owner cannot, they would orphan the merchant. */
    @Transactional
    public void leaveTeam(UUID userId) {
        MerchantMember member =
                members.findByUserId(userId)
                        .orElseThrow(
                                () ->
                                        FincoreException.conflict(
                                                "OWNER_CANNOT_LEAVE",
                                                "The owner cannot leave the merchant account"));
        members.delete(member);
    }

    @Transactional(readOnly = true)
    public TeamInvitePreview previewTeamInvite(String inviteCode) {
        MerchantTeamInvite invite =
                teamInvites
                        .findByReference(inviteCode.trim().toUpperCase())
                        .filter(MerchantTeamInvite::isUsable)
                        .orElseThrow(
                                () -> FincoreException.notFound("Invite not found or expired"));
        return new TeamInvitePreview(
                invite, merchants.findById(invite.getMerchantId()).orElseThrow());
    }

    /** Any customer without a merchant relationship can join a team. No KYC: they hold no money here. */
    public Merchant joinTeam(UUID userId, String inviteCode) {
        requireNoMerchant(userId);
        String code = inviteCode.trim().toUpperCase();
        return tx.execute(
                status -> {
                    MerchantTeamInvite invite =
                            teamInvites
                                    .lockByReference(code)
                                    .filter(MerchantTeamInvite::isUsable)
                                    .orElseThrow(
                                            () ->
                                                    FincoreException.notFound(
                                                            "Invite not found or expired"));
                    Merchant m = merchants.findById(invite.getMerchantId()).orElseThrow();
                    if (members.countByMerchantId(m.getId()) >= planOf(m).maxTeamMembers()) {
                        throw FincoreException.unprocessable(
                                "TEAM_LIMIT", "This team has no room for more members");
                    }
                    members.save(new MerchantMember(m.getId(), userId, invite.getRole(), invite.getLabel()));
                    invite.accept();
                    audit.record(
                            userId.toString(),
                            "MERCHANT_TEAM_JOINED",
                            "MERCHANT",
                            m.getReference(),
                            invite.getRole().name());
                    return m;
                });
    }

    private MerchantMember memberOf(Merchant m, String memberCode) {
        return members.findByReferenceAndMerchantId(memberCode.trim().toUpperCase(), m.getId())
                .orElseThrow(() -> FincoreException.notFound("Team member not found"));
    }

    /** A main merchant reads the payments of one of its outlets (read-only, no fee details). */
    @Transactional(readOnly = true)
    public List<MerchantPayment> outletPayments(UUID userId, String outletCode) {
        Merchant m = requireMain(userId, MerchantPermission.VIEW_NETWORK);
        Merchant outlet =
                merchants
                        .findByReferenceAndParentMerchantId(
                                outletCode.trim().toUpperCase(), m.getId())
                        .orElseThrow(() -> FincoreException.notFound("Outlet not found"));
        return payments.findByMerchantIdOrderByCreatedAtDesc(
                outlet.getId(), PageRequest.of(0, 100));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private Instant startOfDay() {
        return LocalDate.now(props.zone()).atStartOfDay(props.zone()).toInstant();
    }

    private Instant startOfMonth() {
        return LocalDate.now(props.zone()).withDayOfMonth(1).atStartOfDay(props.zone()).toInstant();
    }

    /** FC1000000001 becomes FC******0001. */
    static String mask(String accountNumber) {
        int n = accountNumber.length();
        if (n <= 6) {
            return "****";
        }
        return accountNumber.substring(0, 2) + "*".repeat(n - 6) + accountNumber.substring(n - 4);
    }
}
