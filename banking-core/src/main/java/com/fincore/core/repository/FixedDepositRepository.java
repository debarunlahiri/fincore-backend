package com.fincore.core.repository;

import com.fincore.core.entity.FixedDeposit;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FixedDepositRepository extends JpaRepository<FixedDeposit, UUID> {

    List<FixedDeposit> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from FixedDeposit d where d.reference = :reference and d.ownerUserId = :owner")
    Optional<FixedDeposit> lockByReferenceAndOwner(
            @Param("reference") String reference, @Param("owner") UUID owner);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from FixedDeposit d where d.id = :id")
    Optional<FixedDeposit> lockById(@Param("id") UUID id);

    Optional<FixedDeposit> findByReferenceAndOwnerUserId(String reference, UUID owner);

    List<FixedDeposit> findByStatusAndMaturityDateLessThanEqual(
            FixedDeposit.Status status, LocalDate date);
}
