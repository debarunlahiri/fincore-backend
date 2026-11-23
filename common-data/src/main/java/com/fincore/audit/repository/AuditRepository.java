package com.fincore.audit.repository;

import com.fincore.audit.entity.AuditEntry;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditEntry, UUID> {

    Page<AuditEntry> findByActorOrderByCreatedAtDesc(String actor, Pageable pageable);

    Page<AuditEntry> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
