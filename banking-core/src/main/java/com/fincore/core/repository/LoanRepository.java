package com.fincore.core.repository;

import com.fincore.core.entity.Loan;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanRepository extends JpaRepository<Loan, UUID> {

    List<Loan> findByOwnerUserIdOrderByCreatedAtDesc(UUID ownerUserId);

    Optional<Loan> findByReferenceAndOwnerUserId(String reference, UUID ownerUserId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Loan l where l.reference = :reference and l.ownerUserId = :owner")
    Optional<Loan> lockByReferenceAndOwner(
            @Param("reference") String reference, @Param("owner") UUID owner);

    long countByOwnerUserIdAndStatusIn(UUID ownerUserId, Collection<Loan.Status> statuses);

    @Query(
            "select coalesce(sum(l.emi), 0) from Loan l where l.ownerUserId = :owner and l.status in :statuses")
    BigDecimal sumEmi(
            @Param("owner") UUID owner, @Param("statuses") Collection<Loan.Status> statuses);
}
