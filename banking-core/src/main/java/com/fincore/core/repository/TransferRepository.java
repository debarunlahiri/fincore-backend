package com.fincore.core.repository;

import com.fincore.core.entity.Transfer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByInitiatedByAndIdempotencyKey(UUID initiatedBy, String idempotencyKey);

    Optional<Transfer> findByReferenceAndInitiatedBy(String reference, UUID initiatedBy);

    /**
     * Transfers the user sent or received on one of their accounts, newest first, with optional
     * filters.
     */
    @Query(
            """
            select t from Transfer t
            where (t.initiatedBy = :userId or t.targetAccountId in (select a.id from Account a where a.ownerUserId = :userId))
              and (:accountNumber = '' or t.sourceAccountNumber = :accountNumber or t.targetAccountNumber = :accountNumber)
              and t.createdAt >= :from and t.createdAt < :to
              and t.amount >= :minAmount and t.amount <= :maxAmount
              and (:q = '' or lower(t.reference) like concat('%', :q, '%') or lower(coalesce(t.remarks, '')) like concat('%', :q, '%'))
            order by t.createdAt desc
            """)
    org.springframework.data.domain.Page<Transfer> history(
            @Param("userId") UUID userId,
            @Param("accountNumber") String accountNumber,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("minAmount") BigDecimal minAmount,
            @Param("maxAmount") BigDecimal maxAmount,
            @Param("q") String q,
            org.springframework.data.domain.Pageable pageable);

    @Query(
            "select coalesce(sum(t.amount), 0) from Transfer t where t.sourceAccountId = :accountId and t.createdAt >= :since")
    BigDecimal sumAmountSince(@Param("accountId") UUID accountId, @Param("since") Instant since);
}
