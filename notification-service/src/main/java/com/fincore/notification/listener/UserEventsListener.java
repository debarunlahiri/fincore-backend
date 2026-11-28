package com.fincore.notification.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.messaging.service.ProcessedEvents;
import com.fincore.messaging.util.EventHeaders;
import com.fincore.notification.mail.EmailService;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Queues a welcome email when a user registers. Idempotent: a redelivered event is skipped. */
@Component
@ConditionalOnProperty(name = "fincore.kafka.enabled", havingValue = "true")
public class UserEventsListener {

    static final String CONSUMER_NAME = "notification-service-user-events";

    private final EmailService emails;
    private final ProcessedEvents processedEvents;
    private final ObjectMapper objectMapper;

    public UserEventsListener(
            EmailService emails, ProcessedEvents processedEvents, ObjectMapper objectMapper) {
        this.emails = emails;
        this.processedEvents = processedEvents;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "fincore.user-events", groupId = CONSUMER_NAME)
    @Transactional
    public void onUserEvent(ConsumerRecord<String, String> record) throws Exception {
        if (!"UserRegistered".equals(EventHeaders.eventType(record))) {
            return;
        }
        UUID eventId = EventHeaders.eventId(record);
        if (!processedEvents.markProcessed(CONSUMER_NAME, eventId)) {
            return;
        }
        JsonNode json = objectMapper.readTree(record.value());
        String email = json.path("email").asText(null);
        String name = json.path("fullName").asText("there");
        emails.queue(
                eventId,
                email,
                "Welcome to Fincore",
                "Hi " + name + ",\n\nYour Fincore account has been created.\n\nThe Fincore team");
    }
}
