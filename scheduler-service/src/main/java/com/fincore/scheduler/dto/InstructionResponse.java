package com.fincore.scheduler.dto;

import com.fincore.scheduler.entity.Frequency;
import com.fincore.scheduler.entity.InstructionStatus;
import com.fincore.scheduler.entity.StandingInstruction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** Only the reference number and account numbers are exposed, never internal ids. */
public record InstructionResponse(
        String reference,
        String sourceAccountNumber,
        String targetAccountNumber,
        BigDecimal amount,
        Frequency frequency,
        LocalDate nextRunDate,
        InstructionStatus status,
        String lastRunStatus) {
    public static InstructionResponse from(StandingInstruction s) {
        return new InstructionResponse(
                s.getReference(),
                s.getSourceAccountNumber(),
                s.getTargetAccountNumber(),
                s.getAmount().setScale(2, RoundingMode.UNNECESSARY),
                s.getFrequency(),
                s.getNextRunDate(),
                s.getStatus(),
                s.getLastRunStatus());
    }
}
