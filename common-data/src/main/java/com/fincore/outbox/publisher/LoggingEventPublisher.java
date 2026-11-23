package com.fincore.outbox.publisher;

import com.fincore.outbox.entity.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Default adapter until Kafka arrives in Phase 2. Replace by providing another EventPublisher bean + @Primary. */
@Component
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.info("EVENT {} id={} aggregate={}:{} payload={}",
                event.getEventType(), event.getId(), event.getAggregateType(), event.getAggregateId(), event.getPayload());
    }
}
