package com.fincore.scheduler.controller;

import com.fincore.scheduler.dto.CreateRequest;
import com.fincore.scheduler.dto.InstructionResponse;
import com.fincore.scheduler.service.StandingInstructionService;
import com.fincore.security.model.AuthenticatedUser;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class StandingInstructionController {

    private final StandingInstructionService service;
    private final ZoneId zone;

    public StandingInstructionController(StandingInstructionService service, @Value("${fincore.zone:Asia/Kolkata}") ZoneId zone) {
        this.service = service;
        this.zone = zone;
    }

    @PostMapping("/standing-instructions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    public InstructionResponse create(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody CreateRequest r) {
        return InstructionResponse.from(service.create(
                user.userId(), r.sourceAccountId(), r.targetAccountNumber(), r.amount(), r.frequency(),
                r.firstRunDate(), r.remarks()));
    }

    @GetMapping("/standing-instructions")
    @PreAuthorize("hasRole('CUSTOMER')")
    public List<InstructionResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user.userId()).stream().map(InstructionResponse::from).toList();
    }

    @PostMapping("/standing-instructions/{id}/pause")
    @PreAuthorize("hasRole('CUSTOMER')")
    public InstructionResponse pause(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return InstructionResponse.from(service.pause(user.userId(), id));
    }

    @PostMapping("/standing-instructions/{id}/resume")
    @PreAuthorize("hasRole('CUSTOMER')")
    public InstructionResponse resume(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return InstructionResponse.from(service.resume(user.userId(), id));
    }

    @PostMapping("/standing-instructions/{id}/cancel")
    @PreAuthorize("hasRole('CUSTOMER')")
    public InstructionResponse cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return InstructionResponse.from(service.cancel(user.userId(), id));
    }

    /** Operator trigger for the daily job (useful for demos and recovery after downtime). */
    @PostMapping("/admin/standing-instructions/run")
    @PreAuthorize("hasRole('ADMIN')")
    public StandingInstructionService.RunSummary runNow() {
        return service.runDue(LocalDate.now(zone));
    }
}
