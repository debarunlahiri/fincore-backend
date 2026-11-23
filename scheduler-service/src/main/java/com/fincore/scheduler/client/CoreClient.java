package com.fincore.scheduler.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fincore.common.client.InternalRestClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HTTP adapter to banking-core. Retrying is safe: lookups are reads, and the transfer carries a deterministic
 * idempotency key (instruction id + run date), so a retry after a lost response is a replay, never a second payment.
 */
@Component
public class CoreClient implements CorePort {

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AccountRef(UUID id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TransferResponse(boolean replayed) {}

    private final InternalRestClient http;

    public CoreClient(
            @Value("${fincore.clients.core.base-url}") String baseUrl,
            @Value("${fincore.internal.api-key}") String apiKey) {
        this.http = new InternalRestClient(baseUrl, apiKey, Duration.ofSeconds(1), Duration.ofSeconds(10));
    }

    @Override
    @CircuitBreaker(name = "core")
    @Retry(name = "core")
    public void assertOwned(UUID userId, UUID accountId) {
        http.get("/internal/accounts/{accountId}/owner/{userId}", Void.class, accountId, userId);
    }

    @Override
    @CircuitBreaker(name = "core")
    @Retry(name = "core")
    public UUID accountIdByNumber(String accountNumber) {
        return http.get("/internal/accounts/by-number/{number}", AccountRef.class, accountNumber).id();
    }

    @Override
    @CircuitBreaker(name = "core")
    @Retry(name = "core")
    public TransferOutcome transfer(
            UUID userId,
            String idempotencyKey,
            UUID sourceAccountId,
            String targetAccountNumber,
            BigDecimal amount,
            String currency,
            String remarks) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("sourceAccountId", sourceAccountId);
        request.put("targetAccountNumber", targetAccountNumber);
        request.put("amount", amount);
        request.put("currency", currency);
        request.put("remarks", remarks);
        Map<String, Object> body = Map.of("userId", userId, "idempotencyKey", idempotencyKey, "request", request);
        TransferResponse response = http.post("/internal/transfers", body, TransferResponse.class);
        return new TransferOutcome(response.replayed());
    }
}
