package com.fincore.core.dto;

import com.fincore.core.entity.Account;
import com.fincore.core.entity.AccountStatus;
import com.fincore.core.entity.AccountType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

public record AccountResponse(
        String accountNumber,
        AccountType type,
        String currency,
        BigDecimal balance,
        AccountStatus status,
        BigDecimal dailyTransferLimit,
        Instant createdAt) {

    public static AccountResponse from(Account a) {
        return new AccountResponse(
                a.getAccountNumber(),
                a.getType(),
                a.getCurrency(),
                a.getBalance().setScale(2, RoundingMode.UNNECESSARY),
                a.getStatus(),
                a.getDailyTransferLimit().setScale(2, RoundingMode.UNNECESSARY),
                a.getCreatedAt());
    }
}
