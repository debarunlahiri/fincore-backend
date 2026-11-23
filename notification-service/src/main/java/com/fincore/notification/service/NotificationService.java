package com.fincore.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.messaging.service.ProcessedEvents;
import com.fincore.notification.entity.Notification;
import com.fincore.notification.repository.NotificationRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns domain events from other services into (simulated) customer notifications. Every handler is an
 * idempotent consumer: the dedup marker and the notifications are written in one transaction, so a redelivered
 * message is skipped, and a crash mid-way means it is processed again instead of being lost. A malformed payload
 * throws, which sends the record through the retries and finally to the dead-letter topic.
 */
@Service
public class NotificationService {

    public static final String CONSUMER_NAME = "notification-service";

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final ProcessedEvents processedEvents;
    private final ObjectMapper objectMapper;

    public NotificationService(NotificationRepository notifications, ProcessedEvents processedEvents, ObjectMapper objectMapper) {
        this.notifications = notifications;
        this.processedEvents = processedEvents;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void handleTransferCompleted(UUID eventId, String payload) {
        if (!processedEvents.markProcessed(CONSUMER_NAME, eventId)) {
            log.debug("Skipping duplicate event {}", eventId);
            return;
        }
        JsonNode json = parse(payload);
        UUID source = UUID.fromString(required(json, "sourceAccountId"));
        UUID target = UUID.fromString(required(json, "targetAccountId"));
        String amount = required(json, "amount") + " " + required(json, "currency");

        send(eventId, source, "Debit alert: " + amount + " sent from your account");
        send(eventId, target, "Credit alert: " + amount + " received in your account");
    }

    /** PaymentSettled, PaymentRefunded and PaymentFailed share one payload shape. */
    @Transactional
    public void handlePaymentEvent(UUID eventId, String eventType, String payload) {
        if (!processedEvents.markProcessed(CONSUMER_NAME, eventId)) {
            log.debug("Skipping duplicate event {}", eventId);
            return;
        }
        JsonNode json = parse(payload);
        UUID account = UUID.fromString(required(json, "accountId"));
        String amount = required(json, "amount") + " " + required(json, "currency");
        String rail = required(json, "rail");
        String message = switch (eventType) {
            case "PaymentSettled" -> rail + " payment of " + amount + " completed";
            case "PaymentRefunded" -> rail + " payment of " + amount + " failed and was refunded to your account";
            case "PaymentFailed" -> rail + " payment of " + amount + " could not be started (no money was debited)";
            default -> null; // PaymentInitiated and future event types: nothing to tell the customer
        };
        if (message != null) {
            send(eventId, account, message);
        }
    }

    private void send(UUID eventId, UUID accountId, String message) {
        notifications.save(new Notification(eventId, accountId, "SMS", message));
        log.info("SIMULATED SMS to account {}: {}", accountId, message);
    }

    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed event payload", e);
        }
    }

    private static String required(JsonNode json, String field) {
        JsonNode node = json.get(field);
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("Event payload is missing '" + field + "'");
        }
        return node.asText();
    }
}
