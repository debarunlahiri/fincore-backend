package com.fincore.core.controller;

import com.fincore.common.exception.FincoreException;
import com.fincore.core.dto.AccountRef;
import com.fincore.core.repository.AccountRepository;
import com.fincore.core.service.AccountService;
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

    /** The account id when the user owns the account with this number, 404 otherwise. */
    @GetMapping("/by-number/{accountNumber}/owner/{userId}")
    public AccountRef ownedByNumber(@PathVariable String accountNumber, @PathVariable UUID userId) {
        return new AccountRef(accountService.ownedAccountId(userId, accountNumber));
    }

    @GetMapping("/by-number/{accountNumber}")
    public AccountRef byNumber(@PathVariable String accountNumber) {
        return accounts.findIdByAccountNumber(accountNumber.trim())
                .map(AccountRef::new)
                .orElseThrow(() -> FincoreException.notFound("Account not found"));
    }
}
