package com.fincore.ledger.controller;

import com.fincore.ledger.dto.CashRequest;
import com.fincore.ledger.dto.CashResponse;
import com.fincore.ledger.service.CashService;
import com.fincore.security.model.AuthenticatedUser;
import jakarta.validation.Valid;
import java.math.RoundingMode;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
@PreAuthorize("hasRole('CUSTOMER')")
public class CashController {

    private final CashService cashService;

    public CashController(CashService cashService) {
        this.cashService = cashService;
    }

    @PostMapping("/deposits")
    public CashResponse deposit(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID accountId,
            @Valid @RequestBody CashRequest req) {
        return toResponse(cashService.deposit(user.userId(), accountId, req.amount()));
    }

    @PostMapping("/withdrawals")
    public CashResponse withdraw(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID accountId,
            @Valid @RequestBody CashRequest req) {
        return toResponse(cashService.withdraw(user.userId(), accountId, req.amount()));
    }

    private static CashResponse toResponse(CashService.CashResult r) {
        return new CashResponse(r.journalId(), r.accountId(), r.balance().setScale(2, RoundingMode.UNNECESSARY));
    }
}
