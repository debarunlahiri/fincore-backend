package com.fincore.transfer.controller;

import com.fincore.security.model.AuthenticatedUser;
import com.fincore.transfer.dto.TransferRequest;
import com.fincore.transfer.dto.TransferResponse;
import com.fincore.transfer.dto.TransferResult;
import com.fincore.transfer.service.TransferService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
@PreAuthorize("hasRole('CUSTOMER')")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /** 201 for a new transfer, 200 + {@code Idempotent-Replayed: true} when the key was already processed. */
    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        TransferResult result = transferService.transfer(user, idempotencyKey, request);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.transfer());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.transfer());
    }

    @GetMapping("/{id}")
    public TransferResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return transferService.get(user, id);
    }
}
