package com.fincore.core.client;

import com.fincore.common.client.InternalRestClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Synchronous call to the customer-service, used only when opening an account (KYC gate). Ownership
 * checks on the hot path (transfers, payments) never leave this service because the account stores
 * its owner's user id. Retries and the circuit breaker act only on DependencyUnavailableException
 * (see application.yml), never on business rejections such as "profile not found".
 */
@Component
public class CustomerClient {

    public record CustomerSummary(UUID customerId, UUID userId, String fullName, String kycStatus) {

        public boolean isKycVerified() {
            return "VERIFIED".equals(kycStatus);
        }
    }

    private final InternalRestClient http;

    public CustomerClient(
            @Value("${fincore.clients.customer.base-url}") String baseUrl,
            @Value("${fincore.internal.api-key}") String apiKey) {
        this.http =
                new InternalRestClient(
                        baseUrl, apiKey, Duration.ofSeconds(1), Duration.ofSeconds(3));
    }

    @CircuitBreaker(name = "customer")
    @Retry(name = "customer")
    public CustomerSummary byUser(UUID userId) {
        return http.get("/internal/customers/by-user/{userId}", CustomerSummary.class, userId);
    }
}
