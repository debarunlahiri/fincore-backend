package com.fincore.core.repository;

import com.fincore.core.entity.Journal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalRepository extends JpaRepository<Journal, UUID> {}
