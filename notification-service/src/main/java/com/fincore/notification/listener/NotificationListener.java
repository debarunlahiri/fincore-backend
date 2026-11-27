package com.fincore.notification.listener;

import com.fincore.messaging.util.EventHeaders;
import com.fincore.notification.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Thin Kafka adapters; the transactional, idempotent logic lives in {@link NotificationService}.
 */
@Component
@ConditionalOnProperty(name = "fincore.kafka.enabled", havingValue = "true")
public class NotificationListener {

    private final NotificationService service;

    public NotificationListener(NotificationService service) {
        this.service = service;
    }

    @KafkaListener(topics = "fincore.transfer-events", groupId = NotificationService.CONSUMER_NAME)
    public void onTransferEvent(ConsumerRecord<String, String> record) {
        if ("TransferCompleted".equals(EventHeaders.eventType(record))) {
            service.handleTransferCompleted(EventHeaders.eventId(record), record.value());
        }
    }

    @KafkaListener(topics = "fincore.payment-events", groupId = NotificationService.CONSUMER_NAME)
    public void onPaymentEvent(ConsumerRecord<String, String> record) {
        service.handlePaymentEvent(
                EventHeaders.eventId(record), EventHeaders.eventType(record), record.value());
    }
}
