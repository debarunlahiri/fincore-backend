package com.fincore.transfer.controller;

import com.fincore.security.model.AuthenticatedUser;
import com.fincore.security.model.Role;
import com.fincore.transfer.dto.InternalTransferRequest;
import com.fincore.transfer.dto.TransferResult;
import com.fincore.transfer.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the scheduler-service execute a transfer on behalf of a user, through exactly the same service (fraud
 * rules, limits, locking, ledger, outbox, idempotency) as the public API. The caller supplies the idempotency key.
 */
@RestController
@RequestMapping("/internal/transfers")
@PreAuthorize("hasRole('INTERNAL')")
public class InternalTransferController {

    private final TransferService transferService;

    public InternalTransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public TransferResult transfer(@Valid @RequestBody InternalTransferRequest r) {
        var onBehalfOf = new AuthenticatedUser(r.userId(), "internal-scheduler", Role.CUSTOMER);
        return transferService.transfer(onBehalfOf, r.idempotencyKey(), r.request());
    }
}
