package com.fincore.account.entity;

import com.fincore.common.exception.FincoreException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Balance is a cached projection of the ledger (credits - debits). It is only ever changed through
 * {@code LedgerService}, while holding a pessimistic row lock. {@code version} is a second safety net.
 */
@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true)
    private String accountNumber;

    /** The auth-service user id of the owner (null only for the SYSTEM account). Owned by value, no cross-service FK. */
    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Column(name = "daily_transfer_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal dailyTransferLimit;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Account(String accountNumber, UUID ownerUserId, AccountType type, String currency, BigDecimal dailyTransferLimit) {
        this.accountNumber = accountNumber;
        this.ownerUserId = ownerUserId;
        this.type = type;
        this.currency = currency;
        this.dailyTransferLimit = dailyTransferLimit;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public void credit(BigDecimal amount) {
        balance = balance.add(amount);
    }

    public void debit(BigDecimal amount) {
        if (type != AccountType.SYSTEM && balance.compareTo(amount) < 0) {
            throw FincoreException.unprocessable("INSUFFICIENT_FUNDS", "Insufficient funds in account " + accountNumber);
        }
        balance = balance.subtract(amount);
    }

    public void freeze() {
        status = AccountStatus.FROZEN;
    }

    public void activate() {
        status = AccountStatus.ACTIVE;
    }
}
