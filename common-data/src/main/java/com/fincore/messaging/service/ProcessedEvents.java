package com.fincore.messaging.service;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Idempotent-consumer ledger backed by a (consumer, event_id) primary key in the consuming service's own
 * database. Needs the {@code processed_events} table in that service's migrations.
 */
@Component
public class ProcessedEvents {

    private final JdbcClient jdbc;

    public ProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true if this consumer sees the event for the first time. Atomic even when two consumer threads
     *     race on the same event: only one INSERT affects a row. Call inside the side effect's transaction.
     */
    public boolean markProcessed(String consumer, UUID eventId) {
        return jdbc.sql("insert into processed_events (consumer, event_id) values (?, ?) on conflict do nothing")
                        .param(consumer)
                        .param(eventId)
                        .update()
                == 1;
    }
}
