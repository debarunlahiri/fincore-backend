package com.fincore.transfer.repository;

import com.fincore.transfer.entity.Transfer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByInitiatedByAndIdempotencyKey(UUID initiatedBy, String idempotencyKey);

    Optional<Transfer> findByIdAndInitiatedBy(UUID id, UUID initiatedBy);

    @Query("select coalesce(sum(t.amount), 0) from Transfer t where t.sourceAccountId = :accountId and t.createdAt >= :since")
    BigDecimal sumAmountSince(@Param("accountId") UUID accountId, @Param("since") Instant since);
}
