package com.fincore.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fincore.notification.repository.NotificationRepository;
import com.fincore.notification.service.NotificationService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The idempotent consumer against a real database: the dedup guarantee is a primary-key constraint.
 */
@SpringBootTest
@ActiveProfiles("test")
public class NotificationIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired NotificationService service;
    @Autowired NotificationRepository notifications;

    private static String transfer(UUID source, UUID target) {
        return """
                {"transferId":"%s","sourceAccountId":"%s","targetAccountId":"%s","amount":100.00,"currency":"INR"}
                """
                .formatted(UUID.randomUUID(), source, target);
    }

    @Test
    void firstDeliveryWritesTwoNotifications() {
        UUID eventId = UUID.randomUUID();

        service.handleTransferCompleted(eventId, transfer(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(notifications.countByEventId(eventId)).isEqualTo(2);
    }

    @Test
    void redeliveryOfTheSameEventDoesNotNotifyTwice() {
        UUID eventId = UUID.randomUUID();
        String payload = transfer(UUID.randomUUID(), UUID.randomUUID());

        service.handleTransferCompleted(eventId, payload);
        service.handleTransferCompleted(eventId, payload);
        service.handleTransferCompleted(eventId, payload);

        assertThat(notifications.countByEventId(eventId)).isEqualTo(2);
    }

    @Test
    void concurrentDeliveriesOfTheSameEventNotifyExactlyOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        String payload = transfer(UUID.randomUUID(), UUID.randomUUID());
        var pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                tasks.add(
                        () -> {
                            service.handleTransferCompleted(eventId, payload);
                            return null;
                        });
            }
            for (var future : pool.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(notifications.countByEventId(eventId)).isEqualTo(2);
    }

    @Test
    void aFailedHandlerRollsBackTheDedupMarkerSoTheRetryIsProcessed() {
        UUID eventId = UUID.randomUUID();

        try {
            service.handleTransferCompleted(
                    eventId, "{\"sourceAccountId\":\"" + UUID.randomUUID() + "\"}");
        } catch (IllegalArgumentException expected) {
            // incomplete payload: processing fails after the marker insert, the whole transaction
            // rolls back
        }
        service.handleTransferCompleted(eventId, transfer(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(notifications.countByEventId(eventId)).isEqualTo(2);
    }

    @Test
    void paymentEventsAreDeduplicatedToo() {
        UUID eventId = UUID.randomUUID();
        String payload =
                """
                {"paymentId":"%s","accountId":"%s","rail":"IMPS","amount":10.00,"currency":"INR"}
                """
                        .formatted(UUID.randomUUID(), UUID.randomUUID());

        service.handlePaymentEvent(eventId, "PaymentSettled", payload);
        service.handlePaymentEvent(eventId, "PaymentSettled", payload);

        assertThat(notifications.countByEventId(eventId)).isEqualTo(1);
    }
}
