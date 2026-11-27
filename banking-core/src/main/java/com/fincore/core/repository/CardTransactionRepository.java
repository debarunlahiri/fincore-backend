package com.fincore.core.repository;

import com.fincore.core.entity.CardTransaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardTransactionRepository extends JpaRepository<CardTransaction, UUID> {

    List<CardTransaction> findByCardIdOrderByCreatedAtDesc(UUID cardId, Pageable pageable);

    @Query(
            "select coalesce(sum(t.amount), 0) from CardTransaction t where t.cardId = :cardId and t.status = 'APPROVED' and t.createdAt >= :since")
    BigDecimal approvedSince(@Param("cardId") UUID cardId, @Param("since") Instant since);
}
