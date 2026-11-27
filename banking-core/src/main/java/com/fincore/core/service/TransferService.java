package com.fincore.core.service;

import com.fincore.common.dto.PageResponse;
import com.fincore.common.exception.FincoreException;
import com.fincore.core.config.FincoreProperties;
import com.fincore.core.dto.OtpChallengeResponse;
import com.fincore.core.dto.OtpProof;
import com.fincore.core.dto.TransferHistoryItem;
import com.fincore.core.dto.TransferRequest;
import com.fincore.core.dto.TransferResponse;
import com.fincore.core.dto.TransferResult;
import com.fincore.core.repository.AccountRepository;
import com.fincore.core.repository.TransferRepository;
import com.fincore.security.model.AuthenticatedUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Non-transactional facade. If two requests with the same Idempotency-Key race, the loser's
 * transaction fails on the unique constraint and rolls back completely (no double debit); here we
 * then re-read and return the winner's stored result.
 *
 * <p>Customer transfers additionally pass the banking rules before the money moves: saved and
 * cooled-down payee, per-transaction cap, and a one-time password above the configured amount. The
 * scheduler's internal path skips these (the instruction itself was authorised when it was
 * created).
 */
@Service
public class TransferService {

    public static final String OTP_PURPOSE = "TRANSFER";

    private final TransferExecutor executor;
    private final TransferRepository transfers;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final BeneficiaryService beneficiaries;
    private final OtpService otp;
    private final boolean requireBeneficiary;
    private final BigDecimal otpThreshold;
    private final BigDecimal perTransactionLimit;
    private final FincoreProperties props;

    TransferService(
            TransferExecutor executor,
            TransferRepository transfers,
            AccountRepository accounts,
            AccountService accountService,
            BeneficiaryService beneficiaries,
            OtpService otp,
            @Value("${fincore.transfer.require-beneficiary:true}") boolean requireBeneficiary,
            @Value("${fincore.transfer.otp-threshold:10000}") BigDecimal otpThreshold,
            @Value("${fincore.transfer.per-transaction-limit:200000}")
                    BigDecimal perTransactionLimit,
            FincoreProperties props) {
        this.props = props;
        this.executor = executor;
        this.transfers = transfers;
        this.accounts = accounts;
        this.accountService = accountService;
        this.beneficiaries = beneficiaries;
        this.otp = otp;
        this.requireBeneficiary = requireBeneficiary;
        this.otpThreshold = otpThreshold;
        this.perTransactionLimit = perTransactionLimit;
    }

    /** Internal path (scheduler): no payee or OTP step. */
    public TransferResult transfer(
            AuthenticatedUser user, String idempotencyKey, TransferRequest req) {
        String key = validKey(idempotencyKey);
        return run(user, key, req, fingerprint(req));
    }

    /** Public customer path with the full banking rules. */
    public TransferResult transferAsCustomer(
            AuthenticatedUser user, String idempotencyKey, TransferRequest req, OtpProof proof) {
        String key = validKey(idempotencyKey);
        String hash = fingerprint(req);
        // a retry of an already processed request must replay the stored receipt, not ask for a
        // fresh OTP
        if (transfers.findByInitiatedByAndIdempotencyKey(user.userId(), key).isEmpty()) {
            enforceCustomerRules(user, req, hash, proof);
        }
        return run(user, key, req, hash);
    }

    /**
     * Step 1 of an OTP-protected transfer: the code is bound to exactly this source, target and
     * amount.
     */
    public OtpChallengeResponse requestOtp(AuthenticatedUser user, TransferRequest req) {
        accountService.ownedAccountId(user.userId(), req.sourceAccountNumber());
        return otp.issue(user.userId(), OTP_PURPOSE, fingerprint(req));
    }

    private void enforceCustomerRules(
            AuthenticatedUser user, TransferRequest req, String hash, OtpProof proof) {
        if (req.amount().compareTo(perTransactionLimit) > 0) {
            throw FincoreException.unprocessable(
                    "PER_TRANSACTION_LIMIT",
                    "A single transfer cannot exceed " + perTransactionLimit.toPlainString());
        }
        String target = req.targetAccountNumber().trim();
        boolean ownAccount = accounts.existsByAccountNumberAndOwnerUserId(target, user.userId());
        if (requireBeneficiary && !ownAccount) {
            beneficiaries.assertUsable(user.userId(), target);
        }
        if (req.amount().compareTo(otpThreshold) >= 0) {
            otp.verify(user.userId(), OTP_PURPOSE, hash, proof);
        }
    }

    private TransferResult run(
            AuthenticatedUser user, String key, TransferRequest req, String hash) {
        try {
            return executor.execute(user, key, req, hash);
        } catch (DataIntegrityViolationException race) {
            TransferResult winner = executor.replayFor(user, key, hash);
            if (winner == null) {
                throw race; // a different integrity problem, not an idempotency race
            }
            return winner;
        }
    }

    @Transactional(readOnly = true)
    public TransferResponse get(AuthenticatedUser user, String reference) {
        return transfers
                .findByReferenceAndInitiatedBy(reference.trim().toUpperCase(), user.userId())
                .map(TransferResponse::from)
                .orElseThrow(() -> FincoreException.notFound("Transfer not found"));
    }

    @Transactional(readOnly = true)
    public PageResponse<TransferHistoryItem> history(
            AuthenticatedUser user,
            String accountNumber,
            LocalDate from,
            LocalDate to,
            BigDecimal min,
            BigDecimal max,
            String query,
            int page,
            int size) {
        String account = accountNumber == null ? "" : accountNumber.trim();
        if (!account.isEmpty()) {
            accountService.ownedAccountId(user.userId(), account);
        }
        String q = query == null ? "" : query.trim().toLowerCase();
        var zone = props.zone();
        Page<TransferHistoryItem> result =
                transfers
                        .history(
                                user.userId(),
                                account,
                                from == null ? Instant.EPOCH : from.atStartOfDay(zone).toInstant(),
                                to == null
                                        ? Instant.parse("9999-01-01T00:00:00Z")
                                        : to.plusDays(1).atStartOfDay(zone).toInstant(),
                                min == null ? BigDecimal.ZERO : min,
                                max == null ? new BigDecimal("1000000000000") : max,
                                q,
                                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)))
                        .map(t -> TransferHistoryItem.from(t, user.userId()));
        return new PageResponse<>(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    private static String validKey(String idempotencyKey) {
        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (key.isEmpty() || key.length() > 100) {
            throw FincoreException.badRequest(
                    "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header (1-100 chars) is required");
        }
        return key;
    }

    static String fingerprint(TransferRequest r) {
        String canonical =
                String.join(
                        "|",
                        r.sourceAccountNumber().trim(),
                        r.targetAccountNumber().trim(),
                        r.amount().stripTrailingZeros().toPlainString(),
                        r.currency() == null ? "INR" : r.currency().toUpperCase(),
                        r.remarks() == null ? "" : r.remarks());
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
