package com.fincore.core.repository;

import com.fincore.core.entity.MerchantMember;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantMemberRepository extends JpaRepository<MerchantMember, UUID> {

    Optional<MerchantMember> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    boolean existsByMerchantIdAndUserId(UUID merchantId, UUID userId);

    List<MerchantMember> findByMerchantIdOrderByCreatedAtAsc(UUID merchantId);

    Optional<MerchantMember> findByReferenceAndMerchantId(String reference, UUID merchantId);

    long countByMerchantId(UUID merchantId);
}
