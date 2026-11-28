package com.fincore.core.repository;

import com.fincore.core.entity.MerchantInvite;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MerchantInviteRepository extends JpaRepository<MerchantInvite, UUID> {

    Optional<MerchantInvite> findByReference(String reference);

    /** Row lock, so two people cannot accept the same invite at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from MerchantInvite i where i.reference = :reference")
    Optional<MerchantInvite> lockByReference(@Param("reference") String reference);

    Optional<MerchantInvite> findByReferenceAndParentMerchantId(
            String reference, UUID parentMerchantId);

    List<MerchantInvite> findByParentMerchantIdOrderByCreatedAtDesc(UUID parentMerchantId);

    long countByParentMerchantIdAndStatus(UUID parentMerchantId, String status);
}
