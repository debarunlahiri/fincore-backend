package com.fincore.core.service;

import com.fincore.audit.service.AuditService;
import com.fincore.common.exception.FincoreException;
import com.fincore.core.dto.AddBeneficiaryRequest;
import com.fincore.core.dto.BeneficiaryOtpRequest;
import com.fincore.core.dto.IfscLookupResponse;
import com.fincore.core.dto.OtpChallengeResponse;
import com.fincore.core.entity.AccountType;
import com.fincore.core.entity.Beneficiary;
import com.fincore.core.entity.BeneficiaryType;
import com.fincore.core.model.BankDirectory;
import com.fincore.core.repository.AccountRepository;
import com.fincore.core.repository.BeneficiaryRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Saved payees of three kinds: another FinCore account, an external bank account (account number +
 * IFSC) or a UPI id. Adding one needs a one-time password bound to that exact payee, and it only
 * becomes usable after a cooling period.
 */
@Service
public class BeneficiaryService {

    public static final String OTP_PURPOSE = "BENEFICIARY";
    private static final int MAX_PER_USER = 25;

    /** A validated, normalised payee target. */
    private record Target(
            BeneficiaryType type,
            String identifier,
            String accountNumber,
            String ifsc,
            String bankName,
            String upiId) {}

    private final BeneficiaryRepository beneficiaries;
    private final AccountRepository accounts;
    private final OtpService otp;
    private final AuditService audit;
    private final Duration coolingPeriod;
    private final TransactionTemplate tx;

    public BeneficiaryService(
            BeneficiaryRepository beneficiaries,
            AccountRepository accounts,
            OtpService otp,
            AuditService audit,
            TransactionTemplate tx,
            @Value("${fincore.beneficiary.cooling-period:PT1M}") Duration coolingPeriod) {
        this.beneficiaries = beneficiaries;
        this.accounts = accounts;
        this.otp = otp;
        this.audit = audit;
        this.tx = tx;
        this.coolingPeriod = coolingPeriod;
    }

    public IfscLookupResponse lookupIfsc(String code) {
        String ifsc = code == null ? "" : code.trim().toUpperCase();
        return new IfscLookupResponse(
                ifsc,
                BankDirectory.isValidIfsc(ifsc),
                BankDirectory.bankForIfsc(ifsc).orElse(null));
    }

    /**
     * Validates the payee details first, so the customer learns about a typo before an OTP is sent.
     */
    public OtpChallengeResponse requestOtp(UUID userId, BeneficiaryOtpRequest r) {
        Target target = resolve(userId, r.type(), r.accountNumber(), r.ifsc(), r.upiId());
        return otp.issue(userId, OTP_PURPOSE, target.identifier());
    }

    /**
     * The OTP is verified in its own transaction first, so a wrong code is counted even though the
     * add fails.
     */
    public Beneficiary add(UUID userId, AddBeneficiaryRequest r) {
        Target target = resolve(userId, r.type(), r.accountNumber(), r.ifsc(), r.upiId());
        otp.verify(userId, OTP_PURPOSE, target.identifier(), r.proof());
        return tx.execute(status -> save(userId, r.name().trim(), r.nickname(), target));
    }

    private Beneficiary save(UUID userId, String name, String nickname, Target t) {
        if (beneficiaries.findByOwnerUserIdAndIdentifier(userId, t.identifier()).isPresent()) {
            throw FincoreException.conflict(
                    "BENEFICIARY_EXISTS", "This payee is already in your list");
        }
        if (beneficiaries.countByOwnerUserId(userId) >= MAX_PER_USER) {
            throw FincoreException.unprocessable(
                    "BENEFICIARY_LIMIT", "You can save at most " + MAX_PER_USER + " payees");
        }
        Beneficiary saved =
                beneficiaries.save(
                        new Beneficiary(
                                userId,
                                t.type(),
                                name,
                                nickname == null || nickname.isBlank() ? null : nickname.trim(),
                                t.identifier(),
                                t.accountNumber(),
                                t.ifsc(),
                                t.bankName(),
                                t.upiId(),
                                Instant.now().plus(coolingPeriod)));
        audit.record(
                userId.toString(),
                "BENEFICIARY_ADDED",
                "BENEFICIARY",
                saved.getReference(),
                t.type().name());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Beneficiary> list(UUID userId) {
        return beneficiaries.findByOwnerUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public void remove(UUID userId, String reference) {
        Beneficiary b =
                beneficiaries
                        .findByReferenceAndOwnerUserId(reference.trim().toUpperCase(), userId)
                        .orElseThrow(() -> FincoreException.notFound("Payee not found"));
        beneficiaries.delete(b);
        audit.record(
                userId.toString(), "BENEFICIARY_REMOVED", "BENEFICIARY", b.getReference(), null);
    }

    /**
     * Transfers between FinCore accounts need a saved FinCore payee whose cooling period is over.
     */
    @Transactional(readOnly = true)
    public void assertUsable(UUID userId, String accountNumber) {
        Beneficiary b =
                beneficiaries
                        .findByOwnerUserIdAndIdentifier(userId, accountNumber.trim())
                        .filter(p -> p.getType() == BeneficiaryType.FINCORE)
                        .orElseThrow(
                                () ->
                                        FincoreException.unprocessable(
                                                "BENEFICIARY_REQUIRED",
                                                "Add this account as a payee before sending money to it"));
        if (!b.isUsable()) {
            throw FincoreException.unprocessable(
                    "BENEFICIARY_COOLING", "This payee becomes usable at " + b.getActiveFrom());
        }
    }

    private Target resolve(
            UUID userId, BeneficiaryType type, String accountNumber, String ifsc, String upiId) {
        return switch (type) {
            case FINCORE -> fincore(userId, accountNumber);
            case BANK -> bank(accountNumber, ifsc);
            case UPI -> upi(upiId);
        };
    }

    private Target fincore(UUID userId, String accountNumber) {
        String number =
                required(accountNumber, "accountNumber", "Enter the FinCore account number");
        if (accounts.existsByAccountNumberAndOwnerUserId(number, userId)) {
            throw FincoreException.badRequest(
                    "OWN_ACCOUNT",
                    "That is one of your own accounts, so you do not need a payee for it");
        }
        var id =
                accounts.findIdByAccountNumber(number)
                        .orElseThrow(
                                () ->
                                        FincoreException.notFound(
                                                "No FinCore account exists with that number"));
        if (accounts.findById(id).orElseThrow().getType() == AccountType.SYSTEM) {
            throw FincoreException.badRequest(
                    "INVALID_TARGET", "That account cannot be added as a payee");
        }
        return new Target(BeneficiaryType.FINCORE, number, number, null, "FinCore Bank", null);
    }

    private Target bank(String accountNumber, String ifscInput) {
        String number = required(accountNumber, "accountNumber", "Enter the bank account number");
        if (!BankDirectory.ACCOUNT_NUMBER.matcher(number).matches()) {
            throw FincoreException.badRequest(
                    "INVALID_ACCOUNT_NUMBER", "A bank account number has 9 to 18 digits");
        }
        String ifsc = required(ifscInput, "ifsc", "Enter the branch IFSC code").toUpperCase();
        if (!BankDirectory.isValidIfsc(ifsc)) {
            throw FincoreException.badRequest(
                    "INVALID_IFSC",
                    "IFSC must be 4 letters, a zero, then 6 letters or digits (for example HDFC0001234)");
        }
        String bankName = BankDirectory.bankForIfsc(ifsc).orElse("Other bank");
        return new Target(BeneficiaryType.BANK, ifsc + ":" + number, number, ifsc, bankName, null);
    }

    private Target upi(String upiInput) {
        String vpa = required(upiInput, "upiId", "Enter the UPI id").toLowerCase();
        if (!BankDirectory.UPI_ID.matcher(vpa).matches()) {
            throw FincoreException.badRequest("INVALID_UPI_ID", "A UPI id looks like name@bank");
        }
        return new Target(
                BeneficiaryType.UPI, vpa, null, null, BankDirectory.providerForHandle(vpa), vpa);
    }

    private static String required(String value, String field, String message) {
        if (value == null || value.isBlank()) {
            throw FincoreException.badRequest("MISSING_" + field.toUpperCase(), message);
        }
        return value.trim();
    }
}
