package com.fincore.auth.repository;

import com.fincore.auth.entity.LoginEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginEventRepository extends JpaRepository<LoginEvent, UUID> {

    List<LoginEvent> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
