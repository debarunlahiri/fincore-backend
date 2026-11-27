package com.fincore.core.controller;

import com.fincore.core.dto.CashRequest;
import com.fincore.core.dto.CashResponse;
import com.fincore.core.service.CashService;
import com.fincore.security.model.AuthenticatedUser;
import jakarta.validation.Valid;
import java.math.RoundingMode;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts/{accountNumber}")
@PreAuthorize("hasRole('CUSTOMER')")
public class CashController {

    private final CashService cashService;
    private final com.fincore.core.service.AccountService accountService;

    public CashController(
            CashService cashService, com.fincore.core.service.AccountService accountService) {
        this.cashService = cashService;
        this.accountService = accountService;
    }

    @PostMapping("/deposits")
    public CashResponse deposit(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String accountNumber,
            @Valid @RequestBody CashRequest req) {
        return toResponse(
                cashService.deposit(
                        user.userId(),
                        accountService.ownedAccountId(user.userId(), accountNumber),
                        req.amount()));
    }

    @PostMapping("/withdrawals")
    public CashResponse withdraw(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String accountNumber,
            @Valid @RequestBody CashRequest req) {
        return toResponse(
                cashService.withdraw(
                        user.userId(),
                        accountService.ownedAccountId(user.userId(), accountNumber),
                        req.amount()));
    }

    private static CashResponse toResponse(CashService.CashResult r) {
        return new CashResponse(
                r.reference(),
                r.accountNumber(),
                r.balance().setScale(2, RoundingMode.UNNECESSARY));
    }
}
