package com.fincore;

import com.fincore.common.exception.DependencyUnavailableException;
import com.fincore.common.exception.FincoreException;
import com.fincore.scheduler.client.CorePort;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;

/**
 * In-memory banking-core with the same contract the scheduler relies on: ownership/number lookups, and a
 * transfer that is idempotent on the key (a repeat is reported as {@code replayed}). It can be switched down or
 * told to reject, to exercise the scheduler's failure handling deterministically.
 */
public class FakeCore implements CorePort {

    public record Call(UUID userId, String key, UUID source, String target, BigDecimal amount) {}

    private final Map<UUID, UUID> accountOwners = new ConcurrentHashMap<>();
    private final Map<String, UUID> accountNumbers = new ConcurrentHashMap<>();
    private final Map<String, Call> executed = new ConcurrentHashMap<>();
    private final List<String> attemptedKeys = new ArrayList<>();

    private volatile boolean down;
    private volatile String rejectCode;

    public synchronized void reset() {
        accountOwners.clear();
        accountNumbers.clear();
        executed.clear();
        attemptedKeys.clear();
        down = false;
        rejectCode = null;
    }

    public UUID registerAccount(UUID owner, String number) {
        UUID id = UUID.randomUUID();
        accountOwners.put(id, owner);
        accountNumbers.put(number, id);
        return id;
    }

    public String numberOf(UUID accountId) {
        return accountNumbers.entrySet().stream()
                .filter(e -> e.getValue().equals(accountId))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }

    public void setDown(boolean down) {
        this.down = down;
    }

    public void rejectTransfersWith(String code) {
        this.rejectCode = code;
    }

    public synchronized int effectiveTransfers() {
        return executed.size();
    }

    public synchronized List<String> attemptedKeys() {
        return List.copyOf(attemptedKeys);
    }

    @Override
    public void assertOwned(UUID userId, UUID accountId) {
        checkUp();
        if (!userId.equals(accountOwners.get(accountId))) {
            throw FincoreException.notFound("Account not found");
        }
    }

    @Override
    public UUID accountIdByNumber(String accountNumber) {
        checkUp();
        UUID id = accountNumbers.get(accountNumber);
        if (id == null) {
            throw FincoreException.notFound("Account not found");
        }
        return id;
    }

    @Override
    public synchronized TransferOutcome transfer(
            UUID userId,
            String idempotencyKey,
            UUID sourceAccountId,
            String targetAccountNumber,
            BigDecimal amount,
            String currency,
            String remarks) {
        checkUp();
        attemptedKeys.add(idempotencyKey);
        if (rejectCode != null) {
            throw new FincoreException(HttpStatus.UNPROCESSABLE_ENTITY, rejectCode, "rejected by banking-core");
        }
        if (executed.containsKey(idempotencyKey)) {
            return new TransferOutcome(true);
        }
        executed.put(idempotencyKey, new Call(userId, idempotencyKey, sourceAccountId, targetAccountNumber, amount));
        return new TransferOutcome(false);
    }

    private void checkUp() {
        if (down) {
            throw new DependencyUnavailableException("banking-core is down");
        }
    }
}
