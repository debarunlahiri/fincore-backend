package com.fincore.scheduler.client;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The scheduler's view of banking-core. Every call is safe to retry (reads, or idempotent on the
 * key).
 */
public interface CorePort {

    record TransferOutcome(boolean replayed) {}

    /**
     * @throws com.fincore.common.exception.FincoreException 404 when the user does not own the
     *     account
     * @throws com.fincore.common.exception.DependencyUnavailableException when banking-core cannot
     *     be reached
     */
    void assertOwned(UUID userId, UUID accountId);

    UUID accountIdByNumber(String accountNumber);

    /**
     * Resolves a customer-supplied account number to its internal id; 404 unless the user owns it.
     */
    UUID resolveOwnedAccount(UUID userId, String accountNumber);

    /**
     * Executes the transfer through the normal public path (fraud rules, limits, ledger, outbox).
     *
     * @throws com.fincore.common.exception.FincoreException with banking-core's own status/code for
     *     a business rejection
     */
    TransferOutcome transfer(
            UUID userId,
            String idempotencyKey,
            String sourceAccountNumber,
            String targetAccountNumber,
            BigDecimal amount,
            String currency,
            String remarks);
}
