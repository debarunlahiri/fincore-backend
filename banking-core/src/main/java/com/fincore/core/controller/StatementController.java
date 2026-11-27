package com.fincore.core.controller;

import com.fincore.common.dto.PageResponse;
import com.fincore.core.dto.EntryResponse;
import com.fincore.core.entity.Journal;
import com.fincore.core.entity.LedgerEntry;
import com.fincore.core.repository.JournalRepository;
import com.fincore.core.repository.LedgerEntryRepository;
import com.fincore.core.service.AccountService;
import com.fincore.security.model.AuthenticatedUser;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts/{accountNumber}/statement")
@PreAuthorize("hasRole('CUSTOMER')")
public class StatementController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AccountService accountService;
    private final LedgerEntryRepository entries;
    private final JournalRepository journals;

    public StatementController(
            AccountService accountService,
            LedgerEntryRepository entries,
            JournalRepository journals) {
        this.accountService = accountService;
        this.entries = entries;
        this.journals = journals;
    }

    @GetMapping
    public PageResponse<EntryResponse> statement(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String accountNumber,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID accountId = accountService.ownedAccountId(user.userId(), accountNumber);
        var pageable =
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        var result = entries.findByAccountIdOrderByCreatedAtDescIdDesc(accountId, pageable);
        Map<UUID, Journal> byId =
                journals
                        .findAllById(
                                result.getContent().stream()
                                        .map(LedgerEntry::getJournalId)
                                        .distinct()
                                        .toList())
                        .stream()
                        .collect(Collectors.toMap(Journal::getId, Function.identity()));
        return PageResponse.of(result, e -> EntryResponse.from(e, byId.get(e.getJournalId())));
    }
}
