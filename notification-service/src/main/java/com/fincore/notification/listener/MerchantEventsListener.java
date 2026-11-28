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

/** Emails a receipt to someone who paid a merchant without signing in. */
@Component
@ConditionalOnProperty(name = "fincore.kafka.enabled", havingValue = "true")
public class MerchantEventsListener {

    static final String CONSUMER_NAME = "notification-service-merchant-events";

    private final EmailService emails;
    private final ProcessedEvents processedEvents;
    private final ObjectMapper objectMapper;

    public MerchantEventsListener(
            EmailService emails, ProcessedEvents processedEvents, ObjectMapper objectMapper) {
        this.emails = emails;
        this.processedEvents = processedEvents;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "fincore.merchant-events", groupId = CONSUMER_NAME)
    @Transactional
    public void onMerchantEvent(ConsumerRecord<String, String> record) throws Exception {
        if (!"GuestPaymentReceived".equals(EventHeaders.eventType(record))) {
            return;
        }
        UUID eventId = EventHeaders.eventId(record);
        if (!processedEvents.markProcessed(CONSUMER_NAME, eventId)) {
            return;
        }
        JsonNode p = objectMapper.readTree(record.value());
        String amount = p.path("amount").asText() + " " + p.path("currency").asText("INR");
        String merchant = p.path("businessName").asText();
        String reference = p.path("paymentReference").asText();
        emails.queue(
                eventId,
                p.path("payerEmail").asText(null),
                "Payment receipt: " + amount + " to " + merchant,
                "Hi "
                        + p.path("payerName").asText("there")
                        + ",\n\nYour payment of "
                        + amount
                        + " to "
                        + merchant
                        + " was successful.\n\nReference: "
                        + reference
                        + "\nPaid on: "
                        + p.path("paidAt").asText()
                        + "\n\nKeep this reference if you need to contact the merchant.");
    }
}
