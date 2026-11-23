package com.fincore.ledger.controller;

import com.fincore.account.entity.Account;
import com.fincore.account.service.AccountService;
import com.fincore.common.exception.FincoreException;
import com.fincore.config.FincoreProperties;
import com.fincore.ledger.entity.LedgerEntry;
import com.fincore.ledger.repository.LedgerEntryRepository;
import com.fincore.security.model.AuthenticatedUser;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** CSV statement for a date range (interpreted in the bank's time zone), capped to keep memory bounded. */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/statement.csv")
@PreAuthorize("hasRole('CUSTOMER')")
public class StatementCsvController {

    static final int MAX_ROWS = 10_000;
    private static final int MAX_RANGE_DAYS = 366;

    private final AccountService accountService;
    private final LedgerEntryRepository entries;
    private final FincoreProperties props;

    public StatementCsvController(AccountService accountService, LedgerEntryRepository entries, FincoreProperties props) {
        this.accountService = accountService;
        this.entries = entries;
        this.props = props;
    }

    @GetMapping(produces = "text/csv")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID accountId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from) || from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw FincoreException.badRequest("INVALID_RANGE", "'to' must not precede 'from' and the range is at most one year");
        }
        Account account = accountService.requireOwned(user.userId(), accountId);
        var zone = props.zone();
        var rows = entries.findByAccountIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAscIdAsc(
                accountId,
                from.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant(),
                PageRequest.of(0, MAX_ROWS));

        StringBuilder csv = new StringBuilder("date,journal_id,direction,amount,currency,balance_after\n");
        for (LedgerEntry e : rows) {
            csv.append(e.getCreatedAt().atZone(zone).toLocalDateTime()).append(',')
                    .append(e.getJournalId()).append(',')
                    .append(e.getDirection()).append(',')
                    .append(e.getAmount().toPlainString()).append(',')
                    .append(e.getCurrency()).append(',')
                    .append(e.getBalanceAfter().toPlainString()).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("statement-" + account.getAccountNumber() + "-" + from + "-" + to + ".csv").build().toString())
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }
}
