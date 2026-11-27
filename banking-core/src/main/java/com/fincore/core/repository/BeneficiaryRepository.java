package com.fincore.core.repository;

import com.fincore.core.entity.Beneficiary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, UUID> {

    List<Beneficiary> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);

    Optional<Beneficiary> findByOwnerUserIdAndIdentifier(UUID ownerUserId, String identifier);

    Optional<Beneficiary> findByReferenceAndOwnerUserId(String reference, UUID ownerUserId);

    long countByOwnerUserId(UUID ownerUserId);
}
