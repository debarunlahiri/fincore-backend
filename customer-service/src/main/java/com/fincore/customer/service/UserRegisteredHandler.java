package com.fincore.customer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.messaging.service.ProcessedEvents;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent consumer logic for {@code UserRegistered}. The dedup marker and the profile are written in one
 * transaction; a malformed payload throws so Kafka retries it and finally parks it on the dead-letter topic.
 */
@Service
public class UserRegisteredHandler {

    public static final String CONSUMER_NAME = "customer-service";

    private final CustomerService customers;
    private final ProcessedEvents processedEvents;
    private final ObjectMapper objectMapper;

    public UserRegisteredHandler(CustomerService customers, ProcessedEvents processedEvents, ObjectMapper objectMapper) {
        this.customers = customers;
        this.processedEvents = processedEvents;
        this.objectMapper = objectMapper;
    }

    /** @return true if a profile was created, false for a duplicate delivery */
    @Transactional
    public boolean handle(UUID eventId, String payload) {
        if (!processedEvents.markProcessed(CONSUMER_NAME, eventId)) {
            return false;
        }
        JsonNode json = parse(payload);
        UUID userId = UUID.fromString(required(json, "userId"));
        try {
            customers.provision(
                    userId,
                    required(json, "fullName"),
                    required(json, "phone"),
                    LocalDate.parse(required(json, "dateOfBirth")));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid dateOfBirth in UserRegistered event", e);
        }
        return true;
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
