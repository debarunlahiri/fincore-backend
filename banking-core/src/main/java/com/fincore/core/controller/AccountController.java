package com.fincore.core.controller;

import com.fincore.core.dto.AccountResponse;
import com.fincore.core.dto.OpenAccountRequest;
import com.fincore.core.service.AccountService;
import com.fincore.security.model.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
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
@RequestMapping("/api/v1/accounts")
@PreAuthorize("hasRole('CUSTOMER')")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse open(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody OpenAccountRequest req) {
        return AccountResponse.from(accountService.open(user.userId(), req.type()));
    }

    @GetMapping
    public List<AccountResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return accountService.listOwned(user.userId()).stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{accountNumber}")
    public AccountResponse get(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String accountNumber) {
        return AccountResponse.from(
                accountService.requireOwnedByNumber(user.userId(), accountNumber));
    }
}
