package com.fincore.core.repository;

import com.fincore.core.entity.GuestPayment;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GuestPaymentRepository extends JpaRepository<GuestPayment, UUID> {

    /** Row lock so the checkout callback and the webhook cannot both settle the same payment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from GuestPayment g where g.razorpayOrderId = :orderId")
    Optional<GuestPayment> lockByOrderId(@Param("orderId") String orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from GuestPayment g where g.reference = :reference")
    Optional<GuestPayment> lockByReference(@Param("reference") String reference);
}
