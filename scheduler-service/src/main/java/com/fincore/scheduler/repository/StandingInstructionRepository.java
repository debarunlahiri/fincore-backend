package com.fincore.scheduler.repository;

import com.fincore.scheduler.entity.StandingInstruction;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StandingInstructionRepository extends JpaRepository<StandingInstruction, UUID> {

    List<StandingInstruction> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<StandingInstruction> findByIdAndUserId(UUID id, UUID userId);

    @Query("""
            select s from StandingInstruction s
            where s.status = com.fincore.scheduler.entity.InstructionStatus.ACTIVE and s.nextRunDate <= :today
            order by s.nextRunDate, s.createdAt
            """)
    List<StandingInstruction> findDue(@Param("today") LocalDate today, Pageable pageable);
}
