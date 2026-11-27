package com.fincore.outbox.publisher;

import com.fincore.outbox.entity.OutboxEvent;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes outbox events to Kafka. The record key is the aggregate id, so every event of one
 * transfer or account lands in the same partition and keeps its order. We block for the broker ack:
 * if it never comes, we throw and the relay leaves the event PENDING to retry (at-least-once).
 */
@Component
@Primary
@ConditionalOnProperty(name = "fincore.kafka.enabled", havingValue = "true")
public class KafkaEventPublisher implements EventPublisher {

    public static final String EVENT_ID_HEADER = "eventId";
    public static final String EVENT_TYPE_HEADER = "eventType";

    private final KafkaTemplate<String, String> kafka;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    public static String topicFor(String aggregateType) {
        return "fincore." + aggregateType.toLowerCase() + "-events";
    }

    @Override
    public void publish(OutboxEvent event) {
        var record =
                new ProducerRecord<>(
                        topicFor(event.getAggregateType()),
                        event.getAggregateId().toString(),
                        event.getPayload());
        record.headers()
                .add(EVENT_ID_HEADER, event.getId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers()
                .add(EVENT_TYPE_HEADER, event.getEventType().getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + event.getId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka did not acknowledge event " + event.getId(), e);
        }
    }
}
