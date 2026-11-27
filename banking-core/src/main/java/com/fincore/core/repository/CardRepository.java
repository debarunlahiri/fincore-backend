package com.fincore.core.repository;

import com.fincore.core.entity.Card;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardRepository extends JpaRepository<Card, UUID> {

    List<Card> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);

    Optional<Card> findByReferenceAndOwnerUserId(String reference, UUID ownerUserId);

    long countByOwnerUserIdAndStatusNot(UUID ownerUserId, String status);
}
