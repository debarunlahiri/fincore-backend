package com.fincore.payment.client;

import com.fincore.common.client.InternalRestClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HTTP adapter to banking-core. Automatic retries are safe here precisely because the endpoints are idempotent
 * on the payment id; they apply only to DependencyUnavailableException (see application.yml).
 */
@Component
public class LedgerClient implements LedgerPort {

    private final InternalRestClient http;

    public LedgerClient(
            @Value("${fincore.clients.core.base-url}") String baseUrl,
            @Value("${fincore.internal.api-key}") String apiKey) {
        this.http = new InternalRestClient(baseUrl, apiKey, Duration.ofSeconds(1), Duration.ofSeconds(5));
    }

    @Override
    @CircuitBreaker(name = "ledger")
    @Retry(name = "ledger")
    public Posted debit(UUID paymentId, UUID userId, UUID accountId, BigDecimal amount, String description) {
        return http.post(
                "/internal/ledger/payment-debits",
                Map.of(
                        "paymentId", paymentId,
                        "userId", userId,
                        "accountId", accountId,
                        "amount", amount,
                        "description", description),
                Posted.class);
    }

    @Override
    @CircuitBreaker(name = "ledger")
    @Retry(name = "ledger")
    public Posted reverse(UUID paymentId) {
        return http.post("/internal/ledger/payment-reversals", Map.of("paymentId", paymentId), Posted.class);
    }
}
