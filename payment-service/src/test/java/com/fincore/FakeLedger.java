package com.fincore;

import com.fincore.common.exception.DependencyUnavailableException;
import com.fincore.common.exception.FincoreException;
import com.fincore.payment.client.LedgerPort;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;

/**
 * In-memory stand-in for banking-core with the same contract: both operations are idempotent on the payment id
 * (a repeat returns {@code replayed = true} and the original journal), a reversal needs a prior debit, and the
 * whole thing can be switched "down" or told to reject, to exercise the Saga's failure paths deterministically.
 */
public class FakeLedger implements LedgerPort {

    private final Map<UUID, Posted> debits = new ConcurrentHashMap<>();
    private final Map<UUID, Posted> reversals = new ConcurrentHashMap<>();
    private final AtomicInteger debitCalls = new AtomicInteger();
    private final AtomicInteger reverseCalls = new AtomicInteger();

    private volatile boolean down;
    private volatile String rejectCode;

    public void reset() {
        debits.clear();
        reversals.clear();
        debitCalls.set(0);
        reverseCalls.set(0);
        down = false;
        rejectCode = null;
    }

    public void setDown(boolean down) {
        this.down = down;
    }

    /** Makes every debit fail with a definitive 422 carrying this code (e.g. INSUFFICIENT_FUNDS). */
    public void rejectDebitsWith(String code) {
        this.rejectCode = code;
    }

    public int effectiveDebits() {
        return debits.size();
    }

    public int effectiveReversals() {
        return reversals.size();
    }

    public int debitCalls() {
        return debitCalls.get();
    }

    public int reverseCalls() {
        return reverseCalls.get();
    }

    @Override
    public synchronized Posted debit(UUID paymentId, UUID userId, UUID accountId, BigDecimal amount, String description) {
        debitCalls.incrementAndGet();
        if (down) {
            throw new DependencyUnavailableException("banking-core is down");
        }
        if (rejectCode != null) {
            throw new FincoreException(HttpStatus.UNPROCESSABLE_ENTITY, rejectCode, "rejected by ledger");
        }
        Posted existing = debits.get(paymentId);
        if (existing != null) {
            return new Posted(existing.journalId(), true);
        }
        Posted created = new Posted(UUID.randomUUID(), false);
        debits.put(paymentId, created);
        return created;
    }

    @Override
    public synchronized Posted reverse(UUID paymentId) {
        reverseCalls.incrementAndGet();
        if (down) {
            throw new DependencyUnavailableException("banking-core is down");
        }
        if (!debits.containsKey(paymentId)) {
            throw FincoreException.notFound("No debit was recorded for payment " + paymentId);
        }
        Posted existing = reversals.get(paymentId);
        if (existing != null) {
            return new Posted(existing.journalId(), true);
        }
        Posted created = new Posted(UUID.randomUUID(), false);
        reversals.put(paymentId, created);
        return created;
    }
}
