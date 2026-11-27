package com.fincore.core.repository;

import com.fincore.core.entity.OtpChallenge;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, UUID> {

    Optional<OtpChallenge> findByReferenceAndUserId(String reference, UUID userId);
}
