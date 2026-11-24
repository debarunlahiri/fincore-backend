package com.fincore.account.controller;

import com.fincore.account.dto.AccountResponse;
import com.fincore.account.service.AccountService;
import com.fincore.security.model.AuthenticatedUser;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/accounts/{id}")
@PreAuthorize("hasAnyRole('ADMIN', 'OPERATIONS')")
public class AccountAdminController {

    private final AccountService accountService;

    public AccountAdminController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/freeze")
    public AccountResponse freeze(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id) {
        return AccountResponse.from(accountService.setFrozen(id, true, admin.username()));
    }

    @PostMapping("/unfreeze")
    public AccountResponse unfreeze(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id) {
        return AccountResponse.from(accountService.setFrozen(id, false, admin.username()));
    }
}
