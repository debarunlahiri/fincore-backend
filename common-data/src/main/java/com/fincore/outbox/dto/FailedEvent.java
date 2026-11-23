package com.fincore.outbox.dto;

import java.time.Instant;
import java.util.UUID;

public record FailedEvent(UUID id, String eventType, UUID aggregateId, int attempts, String lastError, Instant createdAt) {}
