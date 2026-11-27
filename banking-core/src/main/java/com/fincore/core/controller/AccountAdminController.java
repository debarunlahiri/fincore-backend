package com.fincore.core.controller;

import com.fincore.core.dto.AccountResponse;
import com.fincore.core.service.AccountService;
import com.fincore.security.model.AuthenticatedUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/accounts/{accountNumber}")
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATIONS')")
public class AccountAdminController {

    private final AccountService accountService;

    public AccountAdminController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/freeze")
    public AccountResponse freeze(
            @AuthenticationPrincipal AuthenticatedUser admin, @PathVariable String accountNumber) {
        return AccountResponse.from(
                accountService.setFrozen(
                        accountService.idForNumber(accountNumber), true, admin.username()));
    }

    @PostMapping("/unfreeze")
    public AccountResponse unfreeze(
            @AuthenticationPrincipal AuthenticatedUser admin, @PathVariable String accountNumber) {
        return AccountResponse.from(
                accountService.setFrozen(
                        accountService.idForNumber(accountNumber), false, admin.username()));
    }
}
