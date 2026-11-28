package com.fincore.core.repository;

import com.fincore.core.entity.MerchantSettlement;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MerchantSettlementRepository extends JpaRepository<MerchantSettlement, UUID> {

    List<MerchantSettlement> findByMerchantIdOrderByCreatedAtDesc(
            UUID merchantId, Pageable pageable);

    /** Royalty a main merchant has received from one outlet. */
    @Query(
            "select coalesce(sum(s.royaltyAmount), 0) from MerchantSettlement s where s.merchantId = :outletId and s.royaltyMerchantId = :parentId")
    BigDecimal royaltyFromOutlet(
            @Param("outletId") UUID outletId, @Param("parentId") UUID parentId);

    @Query(
            "select coalesce(sum(s.royaltyAmount), 0) from MerchantSettlement s where s.royaltyMerchantId = :parentId")
    BigDecimal totalRoyalty(@Param("parentId") UUID parentId);

    @Query(
            "select coalesce(sum(s.feeAmount), 0) from MerchantSettlement s where s.createdAt >= :since")
    BigDecimal feesSettledSince(@Param("since") java.time.Instant since);
}
