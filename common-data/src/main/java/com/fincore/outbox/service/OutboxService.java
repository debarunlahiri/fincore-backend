package com.fincore.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.outbox.entity.OutboxEvent;
import com.fincore.outbox.repository.OutboxRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxService {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * MANDATORY: the event must be written in the same transaction as the state change it
     * describes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent record(
            String aggregateType, UUID aggregateId, String eventType, Object payload) {
        try {
            return repository.save(
                    new OutboxEvent(
                            aggregateType,
                            aggregateId,
                            eventType,
                            objectMapper.writeValueAsString(payload)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise outbox payload for " + eventType, e);
        }
    }
}
