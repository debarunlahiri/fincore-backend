package com.fincore.outbox.relay;

import com.fincore.outbox.entity.OutboxEvent;
import com.fincore.outbox.publisher.EventPublisher;
import com.fincore.outbox.repository.OutboxRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls the service's outbox and hands events to the broker. A crash between publish and commit
 * re-sends the event on the next poll, which is why consumers must be idempotent (at-least-once
 * delivery).
 */
@Component
@EnableScheduling
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final EventPublisher publisher;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(
            OutboxRepository repository,
            EventPublisher publisher,
            @Value("${fincore.outbox.batch-size:50}") int batchSize,
            @Value("${fincore.outbox.max-attempts:5}") int maxAttempts) {
        this.repository = repository;
        this.publisher = publisher;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${fincore.outbox.poll-interval-ms:1000}")
    public void poll() {
        relayBatch();
    }

    /**
     * @return number of events successfully published in this batch
     */
    @Transactional
    public int relayBatch() {
        List<OutboxEvent> batch = repository.claimPending(batchSize);
        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                publisher.publish(event);
                event.markPublished();
                published++;
            } catch (RuntimeException e) {
                event.markAttemptFailed(e.getMessage(), maxAttempts);
                log.warn(
                        "Publishing outbox event {} failed (attempt {}): {}",
                        event.getId(),
                        event.getAttempts(),
                        e.toString());
            }
        }
        return published;
    }
}
