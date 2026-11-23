package com.fincore.audit.dto;

import com.fincore.audit.entity.AuditEntry;
import java.time.Instant;
import java.util.UUID;

public record AuditResponse(
        UUID id, String actor, String action, String entityType, String entityId, String details, Instant createdAt) {

    public static AuditResponse from(AuditEntry e) {
        return new AuditResponse(
                e.getId(), e.getActor(), e.getAction(), e.getEntityType(), e.getEntityId(), e.getDetails(), e.getCreatedAt());
    }
}
