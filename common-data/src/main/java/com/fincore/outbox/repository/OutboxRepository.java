package com.fincore.outbox.repository;

import com.fincore.outbox.entity.OutboxEvent;
import com.fincore.outbox.entity.OutboxStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * FOR UPDATE SKIP LOCKED lets several relay instances poll concurrently: each claims a disjoint
     * batch instead of blocking on, or double-publishing, the same rows.
     */
    @Query(
            value =
                    "select * from outbox_events where status = 'PENDING' order by created_at limit :limit for update skip locked",
            nativeQuery = true)
    List<OutboxEvent> claimPending(@Param("limit") int limit);

    long countByStatus(OutboxStatus status);

    List<OutboxEvent> findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus status);
}
