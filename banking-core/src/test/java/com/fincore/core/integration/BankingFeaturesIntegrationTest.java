package com.fincore.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fincore.AbstractIntegrationTest;
import com.fincore.common.exception.FincoreException;
import com.fincore.core.dto.AddBeneficiaryRequest;
import com.fincore.core.dto.BeneficiaryOtpRequest;
import com.fincore.core.dto.CardPurchaseRequest;
import com.fincore.core.dto.OtpProof;
import com.fincore.core.dto.TransferRequest;
import com.fincore.core.entity.Beneficiary;
import com.fincore.core.entity.BeneficiaryType;
import com.fincore.core.entity.FixedDeposit;
import com.fincore.core.entity.Loan;
import com.fincore.core.entity.LoanType;
import com.fincore.core.service.BeneficiaryService;
import com.fincore.core.service.CardService;
import com.fincore.core.service.DepositService;
import com.fincore.core.service.LoanService;
import com.fincore.core.service.OtpService;
import com.fincore.core.service.TransferService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The banking rules end to end against a real database: payee + OTP gating for transfers, fixed
 * deposits, the loan lifecycle and cards. After every money-moving flow the books must still
 * reconcile.
 */
@TestPropertySource(
        properties = {
            "fincore.transfer.require-beneficiary=true",
            "fincore.transfer.otp-threshold=1000",
            "fincore.beneficiary.cooling-period=PT0S",
            "fincore.otp.expose-code=true"
        })
class BankingFeaturesIntegrationTest extends AbstractIntegrationTest {

    @Autowired TransferService transfers;
    @Autowired BeneficiaryService beneficiaries;
    @Autowired OtpService otp;
    @Autowired DepositService deposits;
    @Autowired LoanService loans;
    @Autowired CardService cards;

    private static String code(Throwable t) {
        return ((FincoreException) t).code();
    }

    private OtpProof proofFor(UUID userId, String purpose, String binding) {
        var challenge = otp.issue(userId, purpose, binding);
        return new OtpProof(challenge.challengeReference(), challenge.devCode());
    }

    private Beneficiary addPayee(TestCustomer owner, TestCustomer payee) {
        String number = payee.account().getAccountNumber();
        var challenge = otp.issue(owner.user().userId(), BeneficiaryService.OTP_PURPOSE, number);
        return beneficiaries.add(
                owner.user().userId(),
                new AddBeneficiaryRequest(
                        BeneficiaryType.FINCORE,
                        "Bob",
                        null,
                        number,
                        null,
                        null,
                        challenge.challengeReference(),
                        challenge.devCode()));
    }

    private Beneficiary addExternal(
            UUID user, BeneficiaryType type, String account, String ifsc, String upi) {
        String binding =
                type == BeneficiaryType.BANK
                        ? ifsc.toUpperCase() + ":" + account
                        : upi.toLowerCase();
        var challenge = otp.issue(user, BeneficiaryService.OTP_PURPOSE, binding);
        return beneficiaries.add(
                user,
                new AddBeneficiaryRequest(
                        type,
                        "Shop",
                        null,
                        account,
                        ifsc,
                        upi,
                        challenge.challengeReference(),
                        challenge.devCode()));
    }

    // ---- payee types
    // ------------------------------------------------------------------------------------------

    @Test
    void upiPayeeIsSavedWithItsProviderAndDuplicatesAreRejectedCaseInsensitively() {
        UUID user = UUID.randomUUID();

        Beneficiary upi = addExternal(user, BeneficiaryType.UPI, null, null, "Shop@OkHDFCBank");

        assertThat(upi.getType()).isEqualTo(BeneficiaryType.UPI);
        assertThat(upi.getUpiId()).isEqualTo("shop@okhdfcbank");
        assertThat(upi.getBankName()).isEqualTo("HDFC Bank");
        assertThat(upi.getReference()).startsWith("BEN");
        assertThatThrownBy(
                        () -> addExternal(user, BeneficiaryType.UPI, null, null, "shop@okhdfcbank"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("BENEFICIARY_EXISTS"));
    }

    @Test
    void bankAccountPayeeKeepsAccountAndIfscAndLooksUpTheBank() {
        UUID user = UUID.randomUUID();

        Beneficiary bank =
                addExternal(user, BeneficiaryType.BANK, "123456789012", "hdfc0001234", null);

        assertThat(bank.getType()).isEqualTo(BeneficiaryType.BANK);
        assertThat(bank.getIfsc()).isEqualTo("HDFC0001234");
        assertThat(bank.getBankName()).isEqualTo("HDFC Bank");
        assertThat(bank.getAccountNumber()).isEqualTo("123456789012");
        // the same number at another branch is a different payee
        assertThat(
                        addExternal(user, BeneficiaryType.BANK, "123456789012", "HDFC0009999", null)
                                .getReference())
                .isNotEqualTo(bank.getReference());
    }

    @Test
    void malformedPayeeDetailsAreRejectedBeforeAnOtpIsEvenSent() {
        UUID user = UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                beneficiaries.requestOtp(
                                        user,
                                        new BeneficiaryOtpRequest(
                                                BeneficiaryType.BANK, "123456789", "BAD", null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("INVALID_IFSC"));
        assertThatThrownBy(
                        () ->
                                beneficiaries.requestOtp(
                                        user,
                                        new BeneficiaryOtpRequest(
                                                BeneficiaryType.BANK, "12ab", "HDFC0001234", null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("INVALID_ACCOUNT_NUMBER"));
        assertThatThrownBy(
                        () ->
                                beneficiaries.requestOtp(
                                        user,
                                        new BeneficiaryOtpRequest(
                                                BeneficiaryType.UPI, null, null, "no-at-sign")))
                .satisfies(e -> assertThat(code(e)).isEqualTo("INVALID_UPI_ID"));
        assertThatThrownBy(
                        () ->
                                beneficiaries.requestOtp(
                                        user,
                                        new BeneficiaryOtpRequest(
                                                BeneficiaryType.UPI, null, null, null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("MISSING_UPIID"));
    }

    @Test
    void youCannotAddYourOwnAccountAsAPayee() {
        TestCustomer owner = newCustomer(BigDecimal.ZERO);

        assertThatThrownBy(
                        () ->
                                beneficiaries.requestOtp(
                                        owner.user().userId(),
                                        new BeneficiaryOtpRequest(
                                                BeneficiaryType.FINCORE,
                                                owner.account().getAccountNumber(),
                                                null,
                                                null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("OWN_ACCOUNT"));
    }

    @Test
    void aBankOrUpiPayeeIsNotAValidTargetForAFinCoreTransfer() {
        TestCustomer a = newCustomer(new BigDecimal("500"));
        addExternal(a.user().userId(), BeneficiaryType.BANK, "123456789012", "HDFC0001234", null);
        var req =
                new TransferRequest(
                        a.account().getAccountNumber(),
                        "123456789012",
                        new BigDecimal("10"),
                        "INR",
                        null);

        assertThatThrownBy(
                        () ->
                                transfers.transferAsCustomer(
                                        a.user(), UUID.randomUUID().toString(), req, null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("BENEFICIARY_REQUIRED"));
    }

    @Test
    void ifscLookupNamesTheBankAndFlagsMalformedCodes() {
        assertThat(beneficiaries.lookupIfsc("sbin0000001").bankName())
                .isEqualTo("State Bank of India");
        assertThat(beneficiaries.lookupIfsc("sbin0000001").valid()).isTrue();
        assertThat(beneficiaries.lookupIfsc("abc").valid()).isFalse();
        var unknown = beneficiaries.lookupIfsc("ZZZZ0000001");
        assertThat(unknown.valid()).isTrue();
        assertThat(unknown.bankName()).isNull();
    }

    @Test
    void payeesAreRemovedByReferenceAndOnlyByTheirOwner() {
        UUID owner = UUID.randomUUID();
        Beneficiary upi = addExternal(owner, BeneficiaryType.UPI, null, null, "friend@ybl");

        assertThatThrownBy(() -> beneficiaries.remove(UUID.randomUUID(), upi.getReference()))
                .isInstanceOf(FincoreException.class);
        beneficiaries.remove(owner, upi.getReference());

        assertThat(beneficiaries.list(owner)).isEmpty();
    }

    // ---- beneficiaries and OTP
    // -------------------------------------------------------------------------------

    @Test
    void transferToSomeoneElseNeedsASavedPayeeFirst() {
        TestCustomer a = newCustomer(new BigDecimal("5000"));
        TestCustomer b = newCustomer(BigDecimal.ZERO);
        var req =
                new TransferRequest(
                        a.account().getAccountNumber(),
                        b.account().getAccountNumber(),
                        new BigDecimal("100"),
                        "INR",
                        null);

        assertThatThrownBy(
                        () ->
                                transfers.transferAsCustomer(
                                        a.user(), UUID.randomUUID().toString(), req, null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("BENEFICIARY_REQUIRED"));

        addPayee(a, b);

        var result =
                transfers.transferAsCustomer(a.user(), UUID.randomUUID().toString(), req, null);
        assertThat(result.transfer().reference()).startsWith("TXN");
        assertThat(balanceOf(b.account())).isEqualByComparingTo("100");
    }

    @Test
    void largeTransfersNeedAOneTimePasswordBoundToThatExactTransfer() {
        TestCustomer a = newCustomer(new BigDecimal("50000"));
        TestCustomer b = newCustomer(BigDecimal.ZERO);
        addPayee(a, b);
        var big =
                new TransferRequest(
                        a.account().getAccountNumber(),
                        b.account().getAccountNumber(),
                        new BigDecimal("5000"),
                        "INR",
                        null);
        var other =
                new TransferRequest(
                        a.account().getAccountNumber(),
                        b.account().getAccountNumber(),
                        new BigDecimal("9000"),
                        "INR",
                        null);

        assertThatThrownBy(
                        () ->
                                transfers.transferAsCustomer(
                                        a.user(), UUID.randomUUID().toString(), big, null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("OTP_REQUIRED"));

        var challenge = transfers.requestOtp(a.user(), big);
        var proof = new OtpProof(challenge.challengeReference(), challenge.devCode());
        // a code issued for 5,000 cannot approve 9,000
        assertThatThrownBy(
                        () ->
                                transfers.transferAsCustomer(
                                        a.user(), UUID.randomUUID().toString(), other, proof))
                .satisfies(e -> assertThat(code(e)).isEqualTo("OTP_INVALID"));

        var fresh = transfers.requestOtp(a.user(), big);
        String key = UUID.randomUUID().toString();
        var done =
                transfers.transferAsCustomer(
                        a.user(),
                        key,
                        big,
                        new OtpProof(fresh.challengeReference(), fresh.devCode()));
        assertThat(done.replayed()).isFalse();

        // retrying the same key after success replays the receipt and does not ask for another code
        var retry = transfers.transferAsCustomer(a.user(), key, big, null);
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.transfer().reference()).isEqualTo(done.transfer().reference());
    }

    @Test
    void anOtpWorksOnceAndWrongGuessesAreLimited() {
        UUID user = UUID.randomUUID();
        var challenge = otp.issue(user, "TRANSFER", "bind");
        var wrong =
                new OtpProof(
                        challenge.challengeReference(),
                        "000000".equals(challenge.devCode()) ? "111111" : "000000");

        for (int i = 0; i < OtpService.MAX_ATTEMPTS; i++) {
            assertThatThrownBy(() -> otp.verify(user, "TRANSFER", "bind", wrong))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("OTP_INVALID"));
        }
        assertThatThrownBy(
                        () ->
                                otp.verify(
                                        user,
                                        "TRANSFER",
                                        "bind",
                                        new OtpProof(
                                                challenge.challengeReference(),
                                                challenge.devCode())))
                .satisfies(e -> assertThat(code(e)).isEqualTo("OTP_LOCKED"));

        var fresh = otp.issue(user, "TRANSFER", "bind");
        var good = new OtpProof(fresh.challengeReference(), fresh.devCode());
        otp.verify(user, "TRANSFER", "bind", good);
        assertThatThrownBy(() -> otp.verify(user, "TRANSFER", "bind", good))
                .satisfies(e -> assertThat(code(e)).isEqualTo("OTP_INVALID"));
    }

    // ---- fixed deposits
    // --------------------------------------------------------------------------------------

    @Test
    void fixedDepositMovesMoneyOutAndEarlyClosureReturnsItWithoutInterestUnderThreeMonths() {
        TestCustomer c = newCustomer(new BigDecimal("20000"));

        FixedDeposit fd =
                deposits.open(
                        c.user().userId(),
                        c.account().getAccountNumber(),
                        new BigDecimal("10000"),
                        12);

        assertThat(fd.getReference()).startsWith("FDR");
        assertThat(fd.getMaturityAmount()).isGreaterThan(new BigDecimal("10000"));
        assertThat(balanceOf(c.account())).isEqualByComparingTo("10000");

        FixedDeposit closed = deposits.closeEarly(c.user().userId(), fd.getReference());

        assertThat(closed.getStatus()).isEqualTo(FixedDeposit.Status.CLOSED_EARLY);
        assertThat(closed.getPayoutAmount()).isEqualByComparingTo("10000");
        assertThat(balanceOf(c.account())).isEqualByComparingTo("20000");
        assertThat(reconciliation.reconcile().consistent()).isTrue();
    }

    @Test
    void anotherCustomerCannotSeeOrCloseMyDeposit() {
        TestCustomer a = newCustomer(new BigDecimal("5000"));
        TestCustomer b = newCustomer(BigDecimal.ZERO);
        FixedDeposit fd =
                deposits.open(
                        a.user().userId(),
                        a.account().getAccountNumber(),
                        new BigDecimal("2000"),
                        6);

        assertThatThrownBy(() -> deposits.get(b.user().userId(), fd.getReference()))
                .isInstanceOf(FincoreException.class);
        assertThatThrownBy(() -> deposits.closeEarly(b.user().userId(), fd.getReference()))
                .isInstanceOf(FincoreException.class);
    }

    // ---- loans
    // -----------------------------------------------------------------------------------------------

    @Test
    void loanIsSanctionedAcceptedWithOtpDisbursedRepaidAndForeclosed() {
        TestCustomer c = newCustomer(new BigDecimal("1000"));
        UUID user = c.user().userId();

        Loan loan =
                loans.apply(
                        user,
                        c.account().getAccountNumber(),
                        LoanType.PERSONAL,
                        new BigDecimal("100000"),
                        12,
                        new BigDecimal("80000"));
        assertThat(loan.getStatus()).isEqualTo(Loan.Status.APPROVED);
        assertThat(loan.getReference()).startsWith("LON");

        var challenge = loans.requestAcceptanceOtp(user, loan.getReference());
        loans.accept(
                user,
                loan.getReference(),
                new OtpProof(challenge.challengeReference(), challenge.devCode()));

        // principal 100,000 less the 1% processing fee arrives in the account
        assertThat(balanceOf(c.account())).isEqualByComparingTo("100000");

        var first = loans.payNextEmi(user, loan.getReference());
        assertThat(first.getStatus().name()).isEqualTo("PAID");
        assertThat(loans.get(user, loan.getReference()).getOutstandingPrincipal())
                .isLessThan(new BigDecimal("100000"));

        cash.deposit(user, c.account().getId(), new BigDecimal("20000")); // cover the payoff amount
        Loan foreclosed = loans.foreclose(user, loan.getReference());
        assertThat(foreclosed.getStatus()).isEqualTo(Loan.Status.FORECLOSED);
        assertThat(reconciliation.reconcile().consistent()).isTrue();
    }

    @Test
    void loanIsRejectedWhenTheEmiExceedsHalfTheIncome() {
        TestCustomer c = newCustomer(BigDecimal.ZERO);

        Loan loan =
                loans.apply(
                        c.user().userId(),
                        c.account().getAccountNumber(),
                        LoanType.PERSONAL,
                        new BigDecimal("500000"),
                        12,
                        new BigDecimal("20000"));

        assertThat(loan.getStatus()).isEqualTo(Loan.Status.REJECTED);
        assertThat(loan.getRejectionReason()).contains("50%");
    }

    @Test
    void loanCannotBeAcceptedWithoutAValidOtp() {
        TestCustomer c = newCustomer(BigDecimal.ZERO);
        Loan loan =
                loans.apply(
                        c.user().userId(),
                        c.account().getAccountNumber(),
                        LoanType.PERSONAL,
                        new BigDecimal("50000"),
                        12,
                        new BigDecimal("90000"));

        assertThatThrownBy(() -> loans.accept(c.user().userId(), loan.getReference(), null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("OTP_REQUIRED"));
    }

    // ---- cards
    // -----------------------------------------------------------------------------------------------

    @Test
    void cardPurchaseDebitsTheAccountAndThreeWrongPinsBlockTheCard() {
        TestCustomer c = newCustomer(new BigDecimal("5000"));
        UUID user = c.user().userId();
        var issued = cards.issue(user, c.account().getAccountNumber(), "VIRTUAL");
        String ref = issued.card().reference();

        assertThat(issued.cardNumber()).hasSize(16);
        assertThat(issued.card().maskedNumber())
                .endsWith(issued.cardNumber().substring(12))
                .doesNotContain(issued.cardNumber());
        cards.setPin(user, ref, "4321");

        var ok =
                cards.purchase(
                        user,
                        ref,
                        new CardPurchaseRequest(
                                "Shop", new BigDecimal("250.00"), "POS", false, "4321", null));
        assertThat(ok.getStatus()).isEqualTo("APPROVED");
        assertThat(balanceOf(c.account())).isEqualByComparingTo("4750");

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(
                            () ->
                                    cards.purchase(
                                            user,
                                            ref,
                                            new CardPurchaseRequest(
                                                    "Shop",
                                                    BigDecimal.TEN,
                                                    "POS",
                                                    false,
                                                    "0000",
                                                    null)))
                    .satisfies(e -> assertThat(code(e)).isEqualTo("CARD_DECLINED"));
        }
        assertThat(cards.get(user, ref).getStatus()).isEqualTo("BLOCKED");
        assertThat(cards.transactions(user, ref)).anyMatch(t -> "DECLINED".equals(t.getStatus()));
        assertThat(reconciliation.reconcile().consistent()).isTrue();
    }

    @Test
    void onlinePurchaseNeedsTheCorrectCvvAndInternationalIsOffByDefault() {
        TestCustomer c = newCustomer(new BigDecimal("5000"));
        UUID user = c.user().userId();
        var issued = cards.issue(user, c.account().getAccountNumber(), "VIRTUAL");
        String ref = issued.card().reference();

        assertThatThrownBy(
                        () ->
                                cards.purchase(
                                        user,
                                        ref,
                                        new CardPurchaseRequest(
                                                "Web",
                                                BigDecimal.TEN,
                                                "ONLINE",
                                                false,
                                                null,
                                                "000".equals(issued.cvv()) ? "111" : "000")))
                .satisfies(e -> assertThat(e.getMessage()).contains("WRONG_CVV"));
        assertThatThrownBy(
                        () ->
                                cards.purchase(
                                        user,
                                        ref,
                                        new CardPurchaseRequest(
                                                "Abroad",
                                                BigDecimal.TEN,
                                                "ONLINE",
                                                true,
                                                null,
                                                issued.cvv())))
                .satisfies(e -> assertThat(e.getMessage()).contains("INTERNATIONAL_DISABLED"));
        assertThat(
                        cards.purchase(
                                        user,
                                        ref,
                                        new CardPurchaseRequest(
                                                "Web",
                                                BigDecimal.TEN,
                                                "ONLINE",
                                                false,
                                                null,
                                                issued.cvv()))
                                .getStatus())
                .isEqualTo("APPROVED");
    }
}
