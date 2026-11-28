package com.fincore.core.repository;

import com.fincore.core.entity.MerchantTeamInvite;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MerchantTeamInviteRepository extends JpaRepository<MerchantTeamInvite, UUID> {

    Optional<MerchantTeamInvite> findByReference(String reference);

    Optional<MerchantTeamInvite> findByReferenceAndMerchantId(String reference, UUID merchantId);

    /** Row lock, so one invite cannot be accepted by two people at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from MerchantTeamInvite i where i.reference = :reference")
    Optional<MerchantTeamInvite> lockByReference(@Param("reference") String reference);

    List<MerchantTeamInvite> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);

    long countByMerchantIdAndStatus(UUID merchantId, String status);
}
