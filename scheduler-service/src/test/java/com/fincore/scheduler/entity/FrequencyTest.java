package com.fincore.scheduler.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fincore.common.exception.FincoreException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

public class FrequencyTest {

    @Test
    void dailyAndWeeklyAdvanceByFixedSteps() {
        assertThat(Frequency.DAILY.next(LocalDate.of(2026, 3, 1))).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(Frequency.WEEKLY.next(LocalDate.of(2026, 3, 1))).isEqualTo(LocalDate.of(2026, 3, 8));
    }

    @Test
    void monthlyClampsToTheEndOfShorterMonths() {
        assertThat(Frequency.MONTHLY.next(LocalDate.of(2026, 1, 31))).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(Frequency.MONTHLY.next(LocalDate.of(2028, 1, 31))).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(Frequency.MONTHLY.next(LocalDate.of(2026, 12, 15))).isEqualTo(LocalDate.of(2027, 1, 15));
    }

    private static StandingInstruction instruction() {
        StandingInstruction si = new StandingInstruction(
                UUID.randomUUID(), UUID.randomUUID(), "FC1", BigDecimal.TEN, "INR", null, Frequency.DAILY,
                LocalDate.of(2026, 3, 1));
        ReflectionTestUtils.setField(si, "id", UUID.randomUUID());
        return si;
    }

    @Test
    void idempotencyKeyIsDeterministicPerInstructionAndDate() {
        StandingInstruction si = instruction();

        assertThat(si.idempotencyKeyFor(LocalDate.of(2026, 3, 1))).isEqualTo(si.idempotencyKeyFor(LocalDate.of(2026, 3, 1)));
        assertThat(si.idempotencyKeyFor(LocalDate.of(2026, 3, 1))).isNotEqualTo(si.idempotencyKeyFor(LocalDate.of(2026, 3, 2)));
        assertThat(si.idempotencyKeyFor(LocalDate.of(2026, 3, 1)).length()).isLessThanOrEqualTo(100);
    }

    @Test
    void recordingARunAdvancesTheSchedule() {
        StandingInstruction si = instruction();

        si.recordRun("OK");

        assertThat(si.getNextRunDate()).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(si.getLastRunStatus()).isEqualTo("OK");
    }

    @Test
    void resumeSkipsMissedRunsInsteadOfFiringABacklog() {
        StandingInstruction si = instruction();
        si.pause();

        si.resume(LocalDate.of(2026, 6, 1));

        assertThat(si.getStatus()).isEqualTo(InstructionStatus.ACTIVE);
        assertThat(si.getNextRunDate()).isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void cancelledInstructionCannotBePausedOrResumed() {
        StandingInstruction si = instruction();
        si.cancel();

        assertThatThrownBy(si::pause).isInstanceOf(FincoreException.class);
        assertThatThrownBy(() -> si.resume(LocalDate.of(2026, 6, 1))).isInstanceOf(FincoreException.class);
    }
}
