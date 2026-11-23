package com.fincore.ledger.service;

import com.fincore.account.entity.Account;
import com.fincore.common.exception.FincoreException;
import com.fincore.ledger.entity.Direction;
import com.fincore.ledger.entity.Journal;
import com.fincore.ledger.entity.JournalType;
import com.fincore.ledger.entity.LedgerEntry;
import com.fincore.ledger.model.Posting;
import com.fincore.ledger.repository.JournalRepository;
import com.fincore.ledger.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only place balances change. Runs inside the caller's transaction (MANDATORY) so the journal, its
 * entries, the balance updates and whatever else the caller writes (transfer row, outbox event) commit
 * or roll back as one unit.
 */
@Service
public class LedgerService {

    private final JournalRepository journals;
    private final LedgerEntryRepository entries;

    public LedgerService(JournalRepository journals, LedgerEntryRepository entries) {
        this.journals = journals;
        this.entries = entries;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Journal post(JournalType type, String description, List<Posting> postings) {
        validate(postings);

        Journal journal = journals.save(new Journal(type, description));
        List<LedgerEntry> created = new ArrayList<>(postings.size());
        for (Posting p : postings) {
            Account account = p.account();
            if (p.direction() == Direction.DEBIT) {
                account.debit(p.amount());
            } else {
                account.credit(p.amount());
            }
            created.add(new LedgerEntry(
                    journal.getId(), account.getId(), p.direction(), p.amount(), account.getCurrency(), account.getBalance()));
        }
        entries.saveAll(created);
        return journal;
    }

    /** Package-visible for unit tests: structural rules that do not need a database. */
    void validate(List<Posting> postings) {
        if (postings == null || postings.size() < 2) {
            throw FincoreException.badRequest("INVALID_JOURNAL", "A journal needs at least two postings");
        }
        Map<String, BigDecimal> netByCurrency = new HashMap<>();
        for (Posting p : postings) {
            if (p.amount() == null || p.amount().signum() <= 0) {
                throw FincoreException.badRequest("INVALID_AMOUNT", "Posting amounts must be positive");
            }
            if (!p.account().isActive()) {
                throw FincoreException.unprocessable(
                        "ACCOUNT_NOT_ACTIVE", "Account " + p.account().getAccountNumber() + " is not active");
            }
            BigDecimal signed = p.direction() == Direction.CREDIT ? p.amount() : p.amount().negate();
            netByCurrency.merge(p.account().getCurrency(), signed, BigDecimal::add);
        }
        boolean balanced = netByCurrency.values().stream().allMatch(v -> v.signum() == 0);
        if (!balanced) {
            throw FincoreException.badRequest("UNBALANCED_JOURNAL", "Debits and credits must balance for each currency");
        }
    }
}
