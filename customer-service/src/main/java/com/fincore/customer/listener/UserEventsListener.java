package com.fincore.customer.listener;

import com.fincore.customer.service.UserRegisteredHandler;
import com.fincore.messaging.util.EventHeaders;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Thin Kafka adapter; the transactional, idempotent logic lives in {@link UserRegisteredHandler}. */
@Component
@ConditionalOnProperty(name = "fincore.kafka.enabled", havingValue = "true")
public class UserEventsListener {

    private final UserRegisteredHandler handler;

    public UserEventsListener(UserRegisteredHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(topics = "fincore.user-events", groupId = UserRegisteredHandler.CONSUMER_NAME)
    public void onUserEvent(ConsumerRecord<String, String> record) {
        if (!"UserRegistered".equals(EventHeaders.eventType(record))) {
            return;
        }
        handler.handle(EventHeaders.eventId(record), record.value());
    }
}
