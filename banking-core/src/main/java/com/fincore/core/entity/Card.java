package com.fincore.core.entity;

import com.fincore.common.util.Reference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The full card number and CVV are shown once at issue. Only the last four digits and hashes are
 * stored.
 */
@Entity
@Table(name = "cards")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Card {

    public static final int MAX_PIN_ATTEMPTS = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String reference = Reference.next("CRD");

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    /** VIRTUAL or PHYSICAL. */
    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String last4;

    @Column(name = "pan_hash", nullable = false, unique = true)
    private String panHash;

    @Column(name = "cvv_hash", nullable = false)
    private String cvvHash;

    @Column(name = "holder_name", nullable = false)
    private String holderName;

    @Column(name = "expiry_month", nullable = false)
    private int expiryMonth;

    @Column(name = "expiry_year", nullable = false)
    private int expiryYear;

    /** ACTIVE, BLOCKED or CLOSED. */
    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "daily_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyLimit;

    @Column(name = "online_enabled", nullable = false)
    private boolean onlineEnabled = true;

    @Column(name = "international_enabled", nullable = false)
    private boolean internationalEnabled = false;

    @Column(name = "pin_hash")
    private String pinHash;

    @Column(name = "pin_failed_attempts", nullable = false)
    private int pinFailedAttempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Card(
            UUID ownerUserId,
            UUID accountId,
            String accountNumber,
            String kind,
            String last4,
            String panHash,
            String cvvHash,
            String holderName,
            YearMonth expiry,
            BigDecimal dailyLimit) {
        this.ownerUserId = ownerUserId;
        this.accountId = accountId;
        this.accountNumber = accountNumber;
        this.kind = kind;
        this.last4 = last4;
        this.panHash = panHash;
        this.cvvHash = cvvHash;
        this.holderName = holderName;
        this.expiryMonth = expiry.getMonthValue();
        this.expiryYear = expiry.getYear();
        this.dailyLimit = dailyLimit;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public boolean isExpired() {
        return YearMonth.now().isAfter(YearMonth.of(expiryYear, expiryMonth));
    }

    public void block() {
        status = "BLOCKED";
    }

    public void unblock() {
        status = "ACTIVE";
        pinFailedAttempts = 0;
    }

    public void close() {
        status = "CLOSED";
    }

    public void setLimits(BigDecimal dailyLimit, boolean online, boolean international) {
        this.dailyLimit = dailyLimit;
        this.onlineEnabled = online;
        this.internationalEnabled = international;
    }

    public void setPin(String pinHash) {
        this.pinHash = pinHash;
        this.pinFailedAttempts = 0;
    }

    /**
     * @return true when this failure just blocked the card
     */
    public boolean recordPinFailure() {
        pinFailedAttempts++;
        if (pinFailedAttempts >= MAX_PIN_ATTEMPTS) {
            block();
            return true;
        }
        return false;
    }

    public void pinOk() {
        pinFailedAttempts = 0;
    }
}
