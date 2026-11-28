package com.fincore.core.repository;

import com.fincore.core.entity.Merchant;
import com.fincore.core.entity.MerchantPlan;
import com.fincore.core.entity.MerchantStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {

    Optional<Merchant> findByOwnerUserId(UUID ownerUserId);

    Optional<Merchant> findByReference(String reference);

    boolean existsByOwnerUserId(UUID ownerUserId);

    List<Merchant> findByParentMerchantIdOrderByCreatedAtAsc(UUID parentMerchantId);

    Optional<Merchant> findByReferenceAndParentMerchantId(String reference, UUID parentMerchantId);

    long countByParentMerchantId(UUID parentMerchantId);

    // ---- back office ----

    @Query(
            "select m from Merchant m where lower(m.businessName) like :q or lower(m.reference) like :q order by m.createdAt desc")
    List<Merchant> search(@Param("q") String q, Pageable pageable);

    @Query(
            "select m from Merchant m where m.status = :status and (lower(m.businessName) like :q or lower(m.reference) like :q) order by m.createdAt desc")
    List<Merchant> searchByStatus(
            @Param("status") MerchantStatus status, @Param("q") String q, Pageable pageable);

    long countByStatus(MerchantStatus status);

    long countByPlan(MerchantPlan plan);

    long countByParentMerchantIdIsNotNull();

    long countBySettlementFailuresGreaterThan(int failures);

    @Query("select coalesce(sum(m.royaltyOwed), 0) from Merchant m")
    BigDecimal totalRoyaltyOwed();

    /** Ids of merchants that have payments waiting for settlement (used by the daily job; merchants in retry back-off are skipped). */
    @Query(
            "select distinct p.merchantId from MerchantPayment p, Merchant m where m.id = p.merchantId and p.settlementId is null and p.status = 'SUCCESS' and p.createdAt <= :cutoff and (m.settlementRetryAt is null or m.settlementRetryAt <= :cutoff)")
    List<UUID> idsWithUnsettledPaymentsBefore(@Param("cutoff") Instant cutoff);
}
