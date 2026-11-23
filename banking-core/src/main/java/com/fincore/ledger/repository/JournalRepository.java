package com.fincore.ledger.repository;

import com.fincore.ledger.entity.Journal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalRepository extends JpaRepository<Journal, UUID> {}
