package com.fincore.account.dto;

import com.fincore.account.entity.Account;
import com.fincore.account.entity.AccountStatus;
import com.fincore.account.entity.AccountType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String accountNumber,
        AccountType type,
        String currency,
        BigDecimal balance,
        AccountStatus status,
        BigDecimal dailyTransferLimit,
        Instant createdAt) {

    public static AccountResponse from(Account a) {
        return new AccountResponse(
                a.getId(),
                a.getAccountNumber(),
                a.getType(),
                a.getCurrency(),
                a.getBalance().setScale(2, RoundingMode.UNNECESSARY),
                a.getStatus(),
                a.getDailyTransferLimit().setScale(2, RoundingMode.UNNECESSARY),
                a.getCreatedAt());
    }
}
