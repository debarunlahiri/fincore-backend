package com.fincore.account.service;

import com.fincore.account.client.CustomerClient;
import com.fincore.account.entity.Account;
import com.fincore.account.entity.AccountStatus;
import com.fincore.account.entity.AccountType;
import com.fincore.account.repository.AccountRepository;
import com.fincore.audit.service.AuditService;
import com.fincore.common.exception.FincoreException;
import com.fincore.config.FincoreProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    public static final String DEFAULT_CURRENCY = "INR";

    private final AccountRepository accounts;
    private final CustomerClient customerClient;
    private final FincoreProperties props;
    private final AuditService audit;

    public AccountService(
            AccountRepository accounts, CustomerClient customerClient, FincoreProperties props, AuditService audit) {
        this.accounts = accounts;
        this.customerClient = customerClient;
        this.props = props;
        this.audit = audit;
    }

    /**
     * The KYC check is a remote call, so it runs before the database transaction starts: no connection or locks
     * are held while waiting for the customer-service.
     */
    public Account open(UUID userId, AccountType type) {
        if (type == AccountType.SYSTEM) {
            throw FincoreException.badRequest("INVALID_ACCOUNT_TYPE", "SYSTEM accounts cannot be opened by customers");
        }
        if (!customerClient.byUser(userId).isKycVerified()) {
            throw FincoreException.unprocessable("KYC_REQUIRED", "KYC must be verified before opening an account");
        }
        return create(userId, type);
    }

    private Account create(UUID userId, AccountType type) {
        // the sequence is non-transactional by design (gaps are fine), and save() is atomic on its own
        String number = "FC" + accounts.nextAccountSequence();
        return accounts.save(
                new Account(number, userId, type, DEFAULT_CURRENCY, props.account().defaultDailyLimit()));
    }

    @Transactional(readOnly = true)
    public List<Account> listOwned(UUID userId) {
        return accounts.findByOwnerUserIdOrderByCreatedAtAsc(userId);
    }

    /** Admin action. Takes the row lock so it cannot interleave with an in-flight posting on the account. */
    @Transactional
    public Account setFrozen(UUID accountId, boolean frozen, String actor) {
        Account account = accounts.lockAllOrdered(List.of(accountId)).stream()
                .findFirst()
                .orElseThrow(() -> FincoreException.notFound("Account not found"));
        if (account.getType() == AccountType.SYSTEM) {
            throw FincoreException.badRequest("INVALID_ACCOUNT_TYPE", "The system account cannot be frozen");
        }
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw FincoreException.conflict("ACCOUNT_CLOSED", "A closed account cannot be changed");
        }
        if (frozen) {
            account.freeze();
        } else {
            account.activate();
        }
        audit.record(actor, frozen ? "ACCOUNT_FROZEN" : "ACCOUNT_UNFROZEN", "ACCOUNT", accountId.toString(), null);
        return account;
    }

    /** Non-locking read for queries. 404 (not 403) for other people's accounts so ids cannot be probed. */
    @Transactional(readOnly = true)
    public Account requireOwned(UUID userId, UUID accountId) {
        return accounts.findByIdAndOwnerUserId(accountId, userId)
                .orElseThrow(() -> FincoreException.notFound("Account not found"));
    }

    /** Cheap scalar ownership probe that does not load the entity (safe to call before taking row locks). */
    @Transactional(readOnly = true)
    public void assertOwned(UUID userId, UUID accountId) {
        if (!accounts.existsByIdAndOwnerUserId(accountId, userId)) {
            throw FincoreException.notFound("Account not found");
        }
    }
}
