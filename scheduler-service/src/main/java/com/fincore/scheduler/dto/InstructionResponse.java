package com.fincore.scheduler.dto;

import com.fincore.scheduler.entity.Frequency;
import com.fincore.scheduler.entity.InstructionStatus;
import com.fincore.scheduler.entity.StandingInstruction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

public record InstructionResponse(
        UUID id,
        UUID sourceAccountId,
        String targetAccountNumber,
        BigDecimal amount,
        Frequency frequency,
        LocalDate nextRunDate,
        InstructionStatus status,
        String lastRunStatus) {

    public static InstructionResponse from(StandingInstruction s) {
        return new InstructionResponse(
                s.getId(),
                s.getSourceAccountId(),
                s.getTargetAccountNumber(),
                s.getAmount().setScale(2, RoundingMode.UNNECESSARY),
                s.getFrequency(),
                s.getNextRunDate(),
                s.getStatus(),
                s.getLastRunStatus());
    }
}
