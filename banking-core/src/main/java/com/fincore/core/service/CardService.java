package com.fincore.core.service;

import com.fincore.audit.service.AuditService;
import com.fincore.common.exception.FincoreException;
import com.fincore.core.client.CustomerClient;
import com.fincore.core.config.FincoreProperties;
import com.fincore.core.dto.CardIssuedResponse;
import com.fincore.core.dto.CardLimitsRequest;
import com.fincore.core.dto.CardPurchaseRequest;
import com.fincore.core.dto.CardResponse;
import com.fincore.core.entity.Account;
import com.fincore.core.entity.Card;
import com.fincore.core.entity.CardTransaction;
import com.fincore.core.entity.Direction;
import com.fincore.core.entity.Journal;
import com.fincore.core.entity.JournalType;
import com.fincore.core.model.Posting;
import com.fincore.core.repository.AccountRepository;
import com.fincore.core.repository.CardRepository;
import com.fincore.core.repository.CardTransactionRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Debit cards on a savings/current account. Issuing shows the number and CVV exactly once. The
 * purchase simulator stands in for a merchant/acquirer: it applies the same checks a real issuer
 * would (status, expiry, channel permissions, PIN or CVV, daily limit, balance) and records every
 * approved and declined attempt. Three wrong PINs block the card.
 */
@Service
public class CardService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BigDecimal DEFAULT_DAILY_LIMIT = new BigDecimal("50000.00");
    private static final BigDecimal MAX_DAILY_LIMIT = new BigDecimal("200000.00");
    private static final int MAX_OPEN_CARDS = 5;

    private final CardRepository cards;
    private final CardTransactionRepository cardTransactions;
    private final AccountService accountService;
    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final AuditService audit;
    private final FincoreProperties props;
    private final TransactionTemplate tx;
    private final CustomerClient customers;
    private final BCryptPasswordEncoder pinEncoder = new BCryptPasswordEncoder();

    public CardService(
            CardRepository cards,
            CardTransactionRepository cardTransactions,
            AccountService accountService,
            AccountRepository accounts,
            LedgerService ledger,
            AuditService audit,
            FincoreProperties props,
            TransactionTemplate tx,
            CustomerClient customers) {
        this.customers = customers;
        this.cards = cards;
        this.cardTransactions = cardTransactions;
        this.accountService = accountService;
        this.accounts = accounts;
        this.ledger = ledger;
        this.audit = audit;
        this.props = props;
        this.tx = tx;
    }

    /**
     * The cardholder name comes from the customer profile, so nobody can print someone else's name
     * on a card. That is a remote call, so it happens before the database transaction starts.
     */
    public CardIssuedResponse issue(UUID userId, String accountNumber, String kind) {
        String holder = holderName(customers.byUser(userId).fullName());
        return tx.execute(status -> issue(userId, accountNumber, kind, holder));
    }

    private CardIssuedResponse issue(
            UUID userId, String accountNumber, String kind, String nameOnCard) {
        Account account = accountService.requireOwnedByNumber(userId, accountNumber);
        if (!account.isActive()) {
            throw FincoreException.unprocessable(
                    "ACCOUNT_NOT_ACTIVE", "Cards can only be issued on an active account");
        }
        if (cards.countByOwnerUserIdAndStatusNot(userId, "CLOSED") >= MAX_OPEN_CARDS) {
            throw FincoreException.unprocessable(
                    "CARD_LIMIT", "You can hold at most " + MAX_OPEN_CARDS + " cards");
        }
        String pan = newPan();
        String cvv = String.format("%03d", RANDOM.nextInt(1000));
        String panHash = sha256(pan);
        Card card =
                cards.save(
                        new Card(
                                userId,
                                account.getId(),
                                account.getAccountNumber(),
                                kind,
                                pan.substring(12),
                                panHash,
                                sha256(cvv + ":" + panHash),
                                nameOnCard,
                                YearMonth.now().plusYears(5),
                                DEFAULT_DAILY_LIMIT));
        audit.record(userId.toString(), "CARD_ISSUED", "CARD", card.getReference(), kind);
        return new CardIssuedResponse(CardResponse.from(card), pan, cvv);
    }

    @Transactional(readOnly = true)
    public List<Card> list(UUID userId) {
        return cards.findByOwnerUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Card get(UUID userId, String reference) {
        return cards.findByReferenceAndOwnerUserId(reference.trim().toUpperCase(), userId)
                .orElseThrow(() -> FincoreException.notFound("Card not found"));
    }

    @Transactional
    public Card block(UUID userId, String reference) {
        Card card = get(userId, reference);
        if ("CLOSED".equals(card.getStatus())) {
            throw FincoreException.conflict("CARD_CLOSED", "This card is closed");
        }
        card.block();
        audit.record(userId.toString(), "CARD_BLOCKED", "CARD", card.getReference(), null);
        return card;
    }

    @Transactional
    public Card unblock(UUID userId, String reference) {
        Card card = get(userId, reference);
        if (!"BLOCKED".equals(card.getStatus())) {
            throw FincoreException.conflict(
                    "CARD_NOT_BLOCKED", "Only a blocked card can be unblocked");
        }
        card.unblock();
        audit.record(userId.toString(), "CARD_UNBLOCKED", "CARD", card.getReference(), null);
        return card;
    }

    @Transactional
    public Card close(UUID userId, String reference) {
        Card card = get(userId, reference);
        card.close();
        audit.record(userId.toString(), "CARD_CLOSED", "CARD", card.getReference(), null);
        return card;
    }

    @Transactional
    public Card setLimits(UUID userId, String reference, CardLimitsRequest r) {
        Card card = get(userId, reference);
        if (r.dailyLimit().compareTo(MAX_DAILY_LIMIT) > 0) {
            throw FincoreException.badRequest(
                    "LIMIT_TOO_HIGH",
                    "Daily card limit cannot exceed " + MAX_DAILY_LIMIT.toPlainString());
        }
        card.setLimits(r.dailyLimit(), r.onlineEnabled(), r.internationalEnabled());
        return card;
    }

    @Transactional
    public Card setPin(UUID userId, String reference, String pin) {
        Card card = get(userId, reference);
        if (!card.isActive()) {
            throw FincoreException.conflict(
                    "CARD_NOT_ACTIVE", "Unblock the card before changing its PIN");
        }
        card.setPin(pinEncoder.encode(pin));
        audit.record(userId.toString(), "CARD_PIN_SET", "CARD", card.getReference(), null);
        return card;
    }

    @Transactional(readOnly = true)
    public List<CardTransaction> transactions(UUID userId, String reference) {
        Card card = get(userId, reference);
        return cardTransactions.findByCardIdOrderByCreatedAtDesc(
                card.getId(), PageRequest.of(0, 50));
    }

    /**
     * Not transactional itself: every decline is recorded in its own committed transaction (so the
     * PIN-failure counter and the decline log survive), and only a fully approved purchase posts to
     * the ledger.
     */
    public CardTransaction purchase(UUID userId, String reference, CardPurchaseRequest r) {
        Card card = get(userId, reference);
        String declined = declineReason(card, r);
        if (declined != null) {
            throw decline(card, r, declined);
        }
        try {
            return tx.execute(status -> approve(card.getId(), r));
        } catch (FincoreException e) {
            if ("INSUFFICIENT_FUNDS".equals(e.code())) {
                throw decline(card, r, "INSUFFICIENT_FUNDS");
            }
            throw e;
        }
    }

    private String declineReason(Card card, CardPurchaseRequest r) {
        if (!card.isActive()) return "CARD_" + card.getStatus();
        if (card.isExpired()) return "CARD_EXPIRED";
        if (r.international() && !card.isInternationalEnabled()) return "INTERNATIONAL_DISABLED";
        if ("ONLINE".equals(r.channel())) {
            if (!card.isOnlineEnabled()) return "ONLINE_DISABLED";
            if (r.cvv() == null
                    || !sha256(r.cvv() + ":" + card.getPanHash()).equals(card.getCvvHash()))
                return "WRONG_CVV";
        } else {
            if (card.getPinHash() == null) return "PIN_NOT_SET";
            if (r.pin() == null || !pinEncoder.matches(r.pin(), card.getPinHash()))
                return "WRONG_PIN";
        }
        BigDecimal usedToday =
                cardTransactions.approvedSince(
                        card.getId(),
                        LocalDate.now(props.zone()).atStartOfDay(props.zone()).toInstant());
        if (usedToday.add(r.amount()).compareTo(card.getDailyLimit()) > 0)
            return "DAILY_LIMIT_EXCEEDED";
        return null;
    }

    private FincoreException decline(Card card, CardPurchaseRequest r, String reason) {
        boolean blockedNow =
                tx.execute(
                        status -> {
                            Card fresh = cards.findById(card.getId()).orElseThrow();
                            boolean blocked = false;
                            if ("WRONG_PIN".equals(reason)) {
                                blocked = fresh.recordPinFailure();
                            } else if ("DAILY_LIMIT_EXCEEDED".equals(reason)) {
                                fresh.pinOk();
                            }
                            cardTransactions.save(
                                    new CardTransaction(
                                            fresh.getId(),
                                            r.merchant(),
                                            r.amount(),
                                            r.channel(),
                                            "DECLINED",
                                            reason,
                                            null));
                            return blocked;
                        });
        String extra =
                blockedNow ? " The card has been blocked after too many wrong PIN attempts." : "";
        return FincoreException.unprocessable(
                "CARD_DECLINED", "Card declined: " + reason + "." + extra);
    }

    private CardTransaction approve(UUID cardId, CardPurchaseRequest r) {
        Card card = cards.findById(cardId).orElseThrow();
        card.pinOk();
        Map<UUID, Account> locked =
                accounts
                        .lockAllOrdered(List.of(card.getAccountId(), CashService.SYSTEM_ACCOUNT_ID))
                        .stream()
                        .collect(Collectors.toMap(Account::getId, Function.identity()));
        Journal journal =
                ledger.post(
                        JournalType.CARD_PURCHASE,
                        "Card purchase at "
                                + r.merchant()
                                + " (card ending "
                                + card.getLast4()
                                + ")",
                        List.of(
                                new Posting(
                                        locked.get(card.getAccountId()),
                                        Direction.DEBIT,
                                        r.amount()),
                                new Posting(
                                        locked.get(CashService.SYSTEM_ACCOUNT_ID),
                                        Direction.CREDIT,
                                        r.amount())));
        return cardTransactions.save(
                new CardTransaction(
                        card.getId(),
                        r.merchant(),
                        r.amount(),
                        r.channel(),
                        "APPROVED",
                        null,
                        journal.getReference()));
    }

    /**
     * Card names are letters and spaces only, upper case, at most 26 characters (the embossing
     * limit).
     */
    static String holderName(String fullName) {
        String cleaned =
                (fullName == null ? "" : fullName)
                        .replaceAll("[^A-Za-z ]", " ")
                        .trim()
                        .replaceAll("\\s+", " ")
                        .toUpperCase();
        if (cleaned.isEmpty()) {
            throw FincoreException.unprocessable(
                    "PROFILE_INCOMPLETE", "Complete your profile before requesting a card");
        }
        return cleaned.length() <= 26 ? cleaned : cleaned.substring(0, 26).trim();
    }

    /** 16 digits starting 5276 with a valid Luhn check digit (simulated network, no real BIN). */
    private static String newPan() {
        StringBuilder sb = new StringBuilder("5276");
        for (int i = 0; i < 11; i++) sb.append(RANDOM.nextInt(10));
        int sum = 0;
        for (int i = 0; i < sb.length(); i++) {
            int d = sb.charAt(sb.length() - 1 - i) - '0';
            if (i % 2 == 0) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
        }
        return sb.append((10 - sum % 10) % 10).toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
