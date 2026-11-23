package com.fincore.ledger.controller;

import com.fincore.account.service.AccountService;
import com.fincore.common.dto.PageResponse;
import com.fincore.ledger.dto.EntryResponse;
import com.fincore.ledger.repository.LedgerEntryRepository;
import com.fincore.security.model.AuthenticatedUser;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/statement")
@PreAuthorize("hasRole('CUSTOMER')")
public class StatementController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AccountService accountService;
    private final LedgerEntryRepository entries;

    public StatementController(AccountService accountService, LedgerEntryRepository entries) {
        this.accountService = accountService;
        this.entries = entries;
    }

    @GetMapping
    public PageResponse<EntryResponse> statement(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        accountService.assertOwned(user.userId(), accountId);
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return PageResponse.of(entries.findByAccountIdOrderByCreatedAtDescIdDesc(accountId, pageable), EntryResponse::from);
    }
}
