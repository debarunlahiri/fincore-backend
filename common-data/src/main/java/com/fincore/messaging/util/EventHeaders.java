package com.fincore.messaging.util;

import com.fincore.outbox.publisher.KafkaEventPublisher;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

/** Reads the metadata headers the outbox publisher puts on every record. */
public final class EventHeaders {

    private EventHeaders() {}

    public static UUID eventId(ConsumerRecord<?, ?> record) {
        return UUID.fromString(required(record, KafkaEventPublisher.EVENT_ID_HEADER));
    }

    public static String eventType(ConsumerRecord<?, ?> record) {
        return required(record, KafkaEventPublisher.EVENT_TYPE_HEADER);
    }

    private static String required(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null) {
            // a record without our headers is poison: retries then DLT, instead of being silently
            // dropped
            throw new IllegalArgumentException("Missing Kafka header '" + name + "'");
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
