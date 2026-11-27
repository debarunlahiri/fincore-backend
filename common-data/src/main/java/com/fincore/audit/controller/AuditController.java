package com.fincore.audit.controller;

import com.fincore.audit.dto.AuditResponse;
import com.fincore.audit.repository.AuditRepository;
import com.fincore.common.dto.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/audit")
@PreAuthorize("hasAnyRole('ADMIN', 'COMPLIANCE')")
public class AuditController {

    private final AuditRepository repository;

    public AuditController(AuditRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public PageResponse<AuditResponse> list(
            @RequestParam(required = false) String actor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        var result =
                actor == null || actor.isBlank()
                        ? repository.findAllByOrderByCreatedAtDesc(pageable)
                        : repository.findByActorOrderByCreatedAtDesc(actor, pageable);
        return PageResponse.of(result, AuditResponse::from);
    }
}
