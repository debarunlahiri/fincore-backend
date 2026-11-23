package com.fincore.account.controller;

import com.fincore.account.dto.AccountRef;
import com.fincore.account.repository.AccountRepository;
import com.fincore.account.service.AccountService;
import com.fincore.common.exception.FincoreException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Lookups other services need (the scheduler validates standing instructions with these). */
@RestController
@RequestMapping("/internal/accounts")
@PreAuthorize("hasRole('INTERNAL')")
public class InternalAccountController {

    private final AccountService accountService;
    private final AccountRepository accounts;

    public InternalAccountController(AccountService accountService, AccountRepository accounts) {
        this.accountService = accountService;
        this.accounts = accounts;
    }

    /** 204 when the user owns the account, 404 otherwise. */
    @GetMapping("/{accountId}/owner/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assertOwner(@PathVariable UUID accountId, @PathVariable UUID userId) {
        accountService.assertOwned(userId, accountId);
    }

    @GetMapping("/by-number/{accountNumber}")
    public AccountRef byNumber(@PathVariable String accountNumber) {
        return accounts.findIdByAccountNumber(accountNumber.trim())
                .map(AccountRef::new)
                .orElseThrow(() -> FincoreException.notFound("Account not found"));
    }
}
