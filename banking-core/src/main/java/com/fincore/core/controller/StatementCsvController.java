package com.fincore.core.controller;

import com.fincore.common.exception.FincoreException;
import com.fincore.core.config.FincoreProperties;
import com.fincore.core.entity.Account;
import com.fincore.core.entity.Journal;
import com.fincore.core.entity.LedgerEntry;
import com.fincore.core.repository.JournalRepository;
import com.fincore.core.repository.LedgerEntryRepository;
import com.fincore.core.service.AccountService;
import com.fincore.security.model.AuthenticatedUser;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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

/**
 * CSV statement for a date range (interpreted in the bank's time zone), capped to keep memory
 * bounded.
 */
@RestController
@RequestMapping("/api/v1/accounts/{accountNumber}/statement.csv")
@PreAuthorize("hasRole('CUSTOMER')")
public class StatementCsvController {

    static final int MAX_ROWS = 10_000;
    private static final int MAX_RANGE_DAYS = 366;

    private final AccountService accountService;
    private final LedgerEntryRepository entries;
    private final JournalRepository journals;
    private final FincoreProperties props;

    public StatementCsvController(
            AccountService accountService,
            LedgerEntryRepository entries,
            JournalRepository journals,
            FincoreProperties props) {
        this.accountService = accountService;
        this.entries = entries;
        this.journals = journals;
        this.props = props;
    }

    @GetMapping(produces = "text/csv")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String accountNumber,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from) || from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw FincoreException.badRequest(
                    "INVALID_RANGE",
                    "'to' must not precede 'from' and the range is at most one year");
        }
        Account account = accountService.requireOwnedByNumber(user.userId(), accountNumber);
        UUID accountId = account.getId();
        var zone = props.zone();
        var rows =
                entries
                        .findByAccountIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAscIdAsc(
                                accountId,
                                from.atStartOfDay(zone).toInstant(),
                                to.plusDays(1).atStartOfDay(zone).toInstant(),
                                PageRequest.of(0, MAX_ROWS));

        StringBuilder csv =
                new StringBuilder(
                        "date,reference,description,direction,amount,currency,balance_after\n");
        Map<UUID, Journal> byId =
                journals
                        .findAllById(
                                rows.stream().map(LedgerEntry::getJournalId).distinct().toList())
                        .stream()
                        .collect(Collectors.toMap(Journal::getId, Function.identity()));
        for (LedgerEntry e : rows) {
            Journal journal = byId.get(e.getJournalId());
            csv.append(e.getCreatedAt().atZone(zone).toLocalDateTime())
                    .append(',')
                    .append(journal.getReference())
                    .append(',')
                    .append('"')
                    .append(String.valueOf(journal.getDescription()).replace("\"", "\"\""))
                    .append("\",")
                    .append(e.getDirection())
                    .append(',')
                    .append(e.getAmount().toPlainString())
                    .append(',')
                    .append(e.getCurrency())
                    .append(',')
                    .append(e.getBalanceAfter().toPlainString())
                    .append('\n');
        }
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(
                                        "statement-"
                                                + account.getAccountNumber()
                                                + "-"
                                                + from
                                                + "-"
                                                + to
                                                + ".csv")
                                .build()
                                .toString())
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }
}
