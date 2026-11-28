package com.fincore.core.repository;

import com.fincore.core.entity.MerchantPayment;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MerchantPaymentRepository extends JpaRepository<MerchantPayment, UUID> {

    List<MerchantPayment> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    Optional<MerchantPayment> findByReferenceAndMerchantId(String reference, UUID merchantId);

    /** Gross volume taken since the instant. Refunded payments still count, so limits cannot be recycled. */
    @Query(
            "select coalesce(sum(p.amount), 0) from MerchantPayment p where p.merchantId = :merchantId and p.createdAt >= :since")
    BigDecimal volumeSince(@Param("merchantId") UUID merchantId, @Param("since") Instant since);

    @Query(
            "select count(p) from MerchantPayment p where p.merchantId = :merchantId and p.createdAt >= :since")
    long countSince(@Param("merchantId") UUID merchantId, @Param("since") Instant since);

    /** What an account has paid to merchants today, counted against the account's daily limit. */
    @Query(
            "select coalesce(sum(p.amount), 0) from MerchantPayment p where p.payerAccountId = :accountId and p.status = 'SUCCESS' and p.createdAt >= :since")
    BigDecimal paidByAccountSince(
            @Param("accountId") UUID accountId, @Param("since") Instant since);

    /** Scalar read so the payer account can be locked before the payment itself is loaded. */
    @Query(
            "select p.payerAccountId from MerchantPayment p where p.reference = :reference and p.merchantId = :merchantId")
    Optional<UUID> findPayerAccountId(
            @Param("reference") String reference, @Param("merchantId") UUID merchantId);

    /** Payments that are paid and old enough to settle, oldest first. */
    @Query(
            "select p from MerchantPayment p where p.merchantId = :merchantId and p.settlementId is null and p.status = 'SUCCESS' and p.createdAt <= :cutoff order by p.createdAt")
    List<MerchantPayment> settleable(
            @Param("merchantId") UUID merchantId, @Param("cutoff") Instant cutoff);

    @Query(
            "select coalesce(sum(p.netAmount), 0) from MerchantPayment p where p.merchantId = :merchantId and p.settlementId is null and p.status = 'SUCCESS'")
    BigDecimal pendingNet(@Param("merchantId") UUID merchantId);

    @Query(
            "select count(p) from MerchantPayment p where p.merchantId = :merchantId and p.settlementId is null and p.status = 'SUCCESS'")
    long pendingCount(@Param("merchantId") UUID merchantId);

    // ---- back office ----

    @Query(
            "select coalesce(sum(p.amount), 0) from MerchantPayment p where p.status = 'SUCCESS' and p.createdAt >= :since")
    BigDecimal platformVolumeSince(@Param("since") Instant since);

    @Query(
            "select coalesce(sum(p.netAmount + p.royalty), 0) from MerchantPayment p where p.settlementId is null and p.status = 'SUCCESS'")
    BigDecimal platformPendingSettlement();
}
