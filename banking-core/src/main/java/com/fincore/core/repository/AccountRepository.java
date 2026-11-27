package com.fincore.core.repository;

import com.fincore.core.entity.Account;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByOwnerUserIdOrderByCreatedAtAsc(UUID ownerUserId);

    Optional<Account> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

    boolean existsByIdAndOwnerUserId(UUID id, UUID ownerUserId);

    boolean existsByAccountNumberAndOwnerUserId(String accountNumber, UUID ownerUserId);

    Optional<Account> findByAccountNumberAndOwnerUserId(String accountNumber, UUID ownerUserId);

    @Query(
            "select a.id from Account a where a.accountNumber = :accountNumber and a.ownerUserId = :ownerUserId")
    Optional<UUID> findIdByAccountNumberAndOwner(
            @Param("accountNumber") String accountNumber, @Param("ownerUserId") UUID ownerUserId);

    @Query("select a.id from Account a where a.accountNumber = :accountNumber")
    Optional<UUID> findIdByAccountNumber(@Param("accountNumber") String accountNumber);

    @Query(value = "select nextval('account_number_seq')", nativeQuery = true)
    long nextAccountSequence();

    /**
     * SELECT ... FOR UPDATE with a deterministic ORDER BY id. Every transaction that needs several
     * accounts locks them in the same global order, so two opposite transfers (A to B and B to A)
     * can never deadlock. Callers must not load these accounts earlier in the same persistence
     * context, or Hibernate would hand back the stale pre-lock state.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> lockAllOrdered(@Param("ids") Collection<UUID> ids);
}
