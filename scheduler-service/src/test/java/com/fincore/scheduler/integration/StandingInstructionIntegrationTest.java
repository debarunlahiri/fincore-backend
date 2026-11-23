package com.fincore.scheduler.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fincore.AbstractIntegrationTest;
import com.fincore.common.exception.FincoreException;
import com.fincore.scheduler.entity.Frequency;
import com.fincore.scheduler.entity.StandingInstruction;
import com.fincore.scheduler.repository.StandingInstructionRepository;
import com.fincore.scheduler.service.StandingInstructionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

public class StandingInstructionIntegrationTest extends AbstractIntegrationTest {

    @Autowired StandingInstructionService service;
    @Autowired StandingInstructionRepository repository;

    private final UUID userId = UUID.randomUUID();
    private UUID sourceAccount;
    private String targetNumber;

    private static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }

    private void accounts() {
        sourceAccount = core.registerAccount(userId, "FC-SRC-" + UUID.randomUUID().toString().substring(0, 6));
        targetNumber = "FC-DST-" + UUID.randomUUID().toString().substring(0, 6);
        core.registerAccount(UUID.randomUUID(), targetNumber);
    }

    private StandingInstruction create(String amount, Frequency frequency) {
        accounts();
        return service.create(userId, sourceAccount, targetNumber, new BigDecimal(amount), frequency, today(), "rent");
    }

    private StandingInstruction reload(StandingInstruction si) {
        return repository.findById(si.getId()).orElseThrow();
    }

    private static String code(Throwable t) {
        return ((FincoreException) t).code();
    }

    @Test
    void dueInstructionTransfersOnceAndSchedulesTheNextRun() {
        StandingInstruction si = create("100", Frequency.MONTHLY);

        var summary = service.runDue(today());

        assertThat(summary.succeeded()).isGreaterThanOrEqualTo(1);
        assertThat(core.attemptedKeys()).contains(si.idempotencyKeyFor(today()));
        StandingInstruction after = reload(si);
        assertThat(after.getNextRunDate()).isEqualTo(today().plusMonths(1));
        assertThat(after.getLastRunStatus()).isEqualTo("OK");
    }

    @Test
    void runningTheJobAgainTheSameDayDoesNothingMore() {
        create("100", Frequency.DAILY);

        service.runDue(today());
        int after = core.effectiveTransfers();
        service.runDue(today());

        assertThat(core.effectiveTransfers()).isEqualTo(after);
    }

    @Test
    void reExecutingTheSameRunDateIsAnIdempotentReplay() {
        StandingInstruction si = create("100", Frequency.MONTHLY);
        service.runDue(today());
        int transfers = core.effectiveTransfers();

        // simulate a second scheduler instance that read the instruction before the first one advanced it
        StandingInstruction stale = reload(si);
        ReflectionTestUtils.setField(stale, "nextRunDate", today());
        repository.save(stale);
        service.runDue(today());

        assertThat(core.effectiveTransfers()).isEqualTo(transfers);
        assertThat(reload(si).getLastRunStatus()).isEqualTo("OK (already executed)");
    }

    @Test
    void businessRejectionIsRecordedAndTheScheduleStillAdvances() {
        StandingInstruction si = create("100", Frequency.DAILY);
        core.rejectTransfersWith("INSUFFICIENT_FUNDS");

        var summary = service.runDue(today());

        assertThat(summary.failed()).isGreaterThanOrEqualTo(1);
        StandingInstruction after = reload(si);
        assertThat(after.getLastRunStatus()).isEqualTo("FAILED: INSUFFICIENT_FUNDS");
        assertThat(after.getNextRunDate()).isEqualTo(today().plusDays(1));
    }

    @Test
    void bankingCoreOutageDefersTheRunWithoutAdvancingOrRecordingAFailure() {
        StandingInstruction si = create("100", Frequency.DAILY);
        core.setDown(true);

        var summary = service.runDue(today());

        assertThat(summary.deferred()).isGreaterThanOrEqualTo(1);
        StandingInstruction after = reload(si);
        assertThat(after.getNextRunDate()).isEqualTo(today());
        assertThat(after.getLastRunStatus()).isNull();
    }

    @Test
    void deferredRunIsPickedUpOnTheNextJobRunOnceCoreIsBack() {
        StandingInstruction si = create("100", Frequency.DAILY);
        core.setDown(true);
        service.runDue(today());
        core.setDown(false);

        service.runDue(today());

        assertThat(core.attemptedKeys()).contains(si.idempotencyKeyFor(today()));
        assertThat(core.effectiveTransfers()).isGreaterThanOrEqualTo(1);
        assertThat(reload(si).getNextRunDate()).isEqualTo(today().plusDays(1));
    }

    @Test
    void missedDaysAreCaughtUpOneRunAtATimeEachWithItsOwnKey() {
        StandingInstruction si = create("100", Frequency.DAILY);
        StandingInstruction behind = reload(si);
        ReflectionTestUtils.setField(behind, "nextRunDate", today().minusDays(2));
        repository.save(behind);

        service.runDue(today());

        assertThat(core.attemptedKeys())
                .contains(
                        si.idempotencyKeyFor(today().minusDays(2)),
                        si.idempotencyKeyFor(today().minusDays(1)),
                        si.idempotencyKeyFor(today()));
        assertThat(reload(si).getNextRunDate()).isEqualTo(today().plusDays(1));
    }

    @Test
    void pausedAndCancelledInstructionsAreNotExecuted() {
        StandingInstruction paused = create("100", Frequency.DAILY);
        StandingInstruction cancelled = service.create(
                userId, sourceAccount, targetNumber, new BigDecimal("200"), Frequency.DAILY, today(), null);
        service.pause(userId, paused.getId());
        service.cancel(userId, cancelled.getId());

        service.runDue(today());

        assertThat(core.attemptedKeys())
                .doesNotContain(paused.idempotencyKeyFor(today()), cancelled.idempotencyKeyFor(today()));
        assertThatThrownBy(() -> service.resume(userId, cancelled.getId())).isInstanceOf(FincoreException.class);
    }

    @Test
    void resumedInstructionRunsAgain() {
        StandingInstruction si = create("100", Frequency.DAILY);
        service.pause(userId, si.getId());
        service.resume(userId, si.getId());

        service.runDue(today());

        assertThat(core.attemptedKeys()).contains(si.idempotencyKeyFor(today()));
    }

    @Test
    void creationValidatesOwnershipTargetAndStartDate() {
        accounts();
        UUID stranger = UUID.randomUUID();

        assertThatThrownBy(() -> service.create(
                        stranger, sourceAccount, targetNumber, BigDecimal.TEN, Frequency.DAILY, today(), null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> service.create(
                        userId, sourceAccount, "FC-UNKNOWN", BigDecimal.TEN, Frequency.DAILY, today(), null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> service.create(
                        userId, sourceAccount, targetNumber, BigDecimal.TEN, Frequency.DAILY, today().minusDays(1), null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("INVALID_START_DATE"));
    }

    @Test
    void transferringToTheSameAccountIsRejectedAtCreation() {
        UUID owner = UUID.randomUUID();
        UUID account = core.registerAccount(owner, "FC-SELF-" + UUID.randomUUID().toString().substring(0, 6));
        String ownNumber = core.numberOf(account);

        assertThatThrownBy(() -> service.create(owner, account, ownNumber, BigDecimal.TEN, Frequency.DAILY, today(), null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("SAME_ACCOUNT"));
    }

    @Test
    void customersOnlySeeAndControlTheirOwnInstructions() {
        StandingInstruction si = create("100", Frequency.DAILY);
        UUID other = UUID.randomUUID();

        assertThat(service.list(userId)).extracting(StandingInstruction::getId).contains(si.getId());
        assertThat(service.list(other)).isEmpty();
        assertThatThrownBy(() -> service.pause(other, si.getId())).isInstanceOf(FincoreException.class);
    }
}
