package com.fincore.outbox.publisher;

import com.fincore.outbox.entity.OutboxEvent;

/**
 * Port to the message broker. Phase 1 ships a logging adapter; Phase 2 adds a Kafka adapter
 * (key = aggregateId so all events of one account/transfer stay ordered within a partition).
 */
public interface EventPublisher {

    /** Must throw if the broker did not acknowledge the event, so the relay retries it later. */
    void publish(OutboxEvent event);
}
