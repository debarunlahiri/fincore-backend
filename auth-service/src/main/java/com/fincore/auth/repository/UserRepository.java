package com.fincore.auth.repository;

import com.fincore.auth.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    java.util.List<User> findByRoleNotOrderByUsername(com.fincore.security.model.Role role);
}
