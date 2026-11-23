package com.fincore.payment.repository;

import com.fincore.payment.entity.Payment;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByInitiatedByAndIdempotencyKey(UUID initiatedBy, String idempotencyKey);

    Optional<Payment> findByIdAndInitiatedBy(UUID id, UUID initiatedBy);

    /** Row lock so that state changes by the request thread and the recovery job cannot interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> lockById(@Param("id") UUID id);

    /** Payments still in flight (REQUESTED or DEBITED) for longer than the cutoff. */
    @Query("""
            select p from Payment p
            where p.status in (com.fincore.payment.entity.PaymentStatus.REQUESTED, com.fincore.payment.entity.PaymentStatus.DEBITED)
              and p.createdAt < :cutoff
            order by p.createdAt
            """)
    List<Payment> findStuck(@Param("cutoff") Instant cutoff, Pageable pageable);
}
