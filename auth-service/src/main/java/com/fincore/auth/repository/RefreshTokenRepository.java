package com.fincore.auth.repository;

import com.fincore.auth.entity.RefreshToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    java.util.List<RefreshToken> findByUserIdAndRevokedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            UUID userId, java.time.Instant now);

    Optional<RefreshToken> findByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Query(
            "update RefreshToken t set t.revoked = true where t.userId = :userId and t.revoked = false")
    int revokeAllForUser(@Param("userId") UUID userId);

    /**
     * Single-session sign-in deletes (not revokes) the old tokens: a revoked token presented later
     * would look like token theft and trigger reuse detection, which would also sign out the device
     * that just logged in.
     */
    @Modifying
    @Query("delete from RefreshToken t where t.userId = :userId")
    int deleteAllForUser(@Param("userId") UUID userId);
}
