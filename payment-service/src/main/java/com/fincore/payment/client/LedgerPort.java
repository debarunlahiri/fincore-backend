package com.fincore.payment.client;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The payment-service's view of the banking-core ledger. Both operations are idempotent on the payment id, so
 * the Saga can safely retry them after a timeout or crash.
 */
public interface LedgerPort {

    record Posted(UUID journalId, boolean replayed) {}

    /**
     * @throws com.fincore.common.exception.DependencyUnavailableException if banking-core cannot be reached (outcome unknown,
     *     retry later)
     * @throws com.fincore.common.exception.FincoreException for a definitive rejection (insufficient funds, not the owner...)
     */
    Posted debit(UUID paymentId, UUID userId, UUID accountId, BigDecimal amount, String description);

    Posted reverse(UUID paymentId);
}
