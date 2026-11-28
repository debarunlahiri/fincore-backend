package com.fincore.notification.mail;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, UUID> {

    @Query(
            "select e from EmailOutbox e where e.status = 'PENDING' and e.nextAttemptAt <= :now"
                    + " order by e.nextAttemptAt")
    List<EmailOutbox> findDue(Instant now, Pageable page);
}
