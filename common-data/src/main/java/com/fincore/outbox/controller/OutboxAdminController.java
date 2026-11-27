package com.fincore.outbox.controller;

import com.fincore.common.exception.FincoreException;
import com.fincore.outbox.dto.FailedEvent;
import com.fincore.outbox.entity.OutboxEvent;
import com.fincore.outbox.entity.OutboxStatus;
import com.fincore.outbox.repository.OutboxRepository;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator tooling for the outbox's dead-letter state. */
@RestController
@RequestMapping("/api/v1/admin/outbox")
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATIONS', 'COMPLIANCE')")
public class OutboxAdminController {

    private final OutboxRepository repository;

    public OutboxAdminController(OutboxRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/summary")
    public Map<OutboxStatus, Long> summary() {
        Map<OutboxStatus, Long> counts = new EnumMap<>(OutboxStatus.class);
        for (OutboxStatus s : OutboxStatus.values()) {
            counts.put(s, repository.countByStatus(s));
        }
        return counts;
    }

    @GetMapping("/failed")
    public List<FailedEvent> failed() {
        return repository.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.FAILED).stream()
                .map(
                        e ->
                                new FailedEvent(
                                        e.getId(),
                                        e.getEventType(),
                                        e.getAggregateId(),
                                        e.getAttempts(),
                                        e.getLastError(),
                                        e.getCreatedAt()))
                .toList();
    }

    /** Safe to replay: consumers deduplicate on the event id. */
    @PostMapping("/{id}/replay")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATIONS')")
    @Transactional
    public FailedEvent replay(@PathVariable UUID id) {
        OutboxEvent e =
                repository
                        .findById(id)
                        .orElseThrow(() -> FincoreException.notFound("Outbox event not found"));
        if (e.getStatus() != OutboxStatus.FAILED) {
            throw FincoreException.conflict("NOT_FAILED", "Only FAILED events can be replayed");
        }
        e.resetForReplay();
        return new FailedEvent(
                e.getId(),
                e.getEventType(),
                e.getAggregateId(),
                e.getAttempts(),
                e.getLastError(),
                e.getCreatedAt());
    }
}
