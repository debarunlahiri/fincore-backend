package com.fincore.audit.service;

import com.fincore.audit.entity.AuditEntry;
import com.fincore.audit.repository.AuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository repository;

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    /**
     * REQUIRES_NEW so the audit row survives even when the calling transaction rolls back (for
     * example a failed login). An audit write failure is logged but never breaks the business
     * operation.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            String actor, String action, String entityType, String entityId, String details) {
        try {
            repository.save(new AuditEntry(actor, action, entityType, entityId, details));
        } catch (RuntimeException e) {
            log.error("Could not write audit entry {} for {}", action, actor, e);
        }
    }
}
