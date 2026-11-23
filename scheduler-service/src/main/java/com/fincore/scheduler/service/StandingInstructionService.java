package com.fincore.scheduler.service;

import com.fincore.common.exception.DependencyUnavailableException;
import com.fincore.common.exception.FincoreException;
import com.fincore.scheduler.client.CorePort;
import com.fincore.scheduler.entity.Frequency;
import com.fincore.scheduler.entity.StandingInstruction;
import com.fincore.scheduler.repository.StandingInstructionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Recurring transfers. Every run goes through banking-core's normal transfer path (same fraud rules, limits,
 * locking, ledger and outbox as a manual transfer) with a deterministic idempotency key per instruction and
 * date, so two scheduler instances or a retried run cannot move the money twice.
 */
@Service
public class StandingInstructionService {

    public record RunSummary(int succeeded, int failed, int deferred) {}

    private enum Result {
        SUCCEEDED,
        FAILED,
        DEFERRED
    }

    private static final Logger log = LoggerFactory.getLogger(StandingInstructionService.class);
    private static final int BATCH_SIZE = 100;
    private static final int MAX_BATCHES_PER_RUN = 100;
    private static final String CURRENCY = "INR";

    private final StandingInstructionRepository instructions;
    private final CorePort core;
    private final ZoneId zone;

    public StandingInstructionService(
            StandingInstructionRepository instructions, CorePort core, @Value("${fincore.zone:Asia/Kolkata}") ZoneId zone) {
        this.instructions = instructions;
        this.core = core;
        this.zone = zone;
    }

    public StandingInstruction create(
            UUID userId,
            UUID sourceAccountId,
            String targetAccountNumber,
            BigDecimal amount,
            Frequency frequency,
            LocalDate firstRun,
            String remarks) {
        if (firstRun.isBefore(LocalDate.now(zone))) {
            throw FincoreException.badRequest("INVALID_START_DATE", "The first run date cannot be in the past");
        }
        core.assertOwned(userId, sourceAccountId);
        UUID targetId = core.accountIdByNumber(targetAccountNumber.trim());
        if (targetId.equals(sourceAccountId)) {
            throw FincoreException.badRequest("SAME_ACCOUNT", "Source and target account must differ");
        }
        return instructions.save(new StandingInstruction(
                userId, sourceAccountId, targetAccountNumber.trim(), amount, CURRENCY, remarks, frequency, firstRun));
    }

    public List<StandingInstruction> list(UUID userId) {
        return instructions.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public StandingInstruction pause(UUID userId, UUID id) {
        StandingInstruction si = require(userId, id);
        si.pause();
        return instructions.save(si);
    }

    public StandingInstruction resume(UUID userId, UUID id) {
        StandingInstruction si = require(userId, id);
        si.resume(LocalDate.now(zone));
        return instructions.save(si);
    }

    public StandingInstruction cancel(UUID userId, UUID id) {
        StandingInstruction si = require(userId, id);
        si.cancel();
        return instructions.save(si);
    }

    /**
     * Executes everything due on or before {@code today}, catching up missed days one run at a time. A business
     * rejection (insufficient funds, frozen account, fraud rule...) is recorded on the instruction and the
     * schedule moves on, so one bad day does not block all later ones. If banking-core is unreachable the run
     * is DEFERRED: nothing is recorded and the schedule does not advance, so the next job run retries it.
     */
    public RunSummary runDue(LocalDate today) {
        int succeeded = 0;
        int failed = 0;
        Set<UUID> deferred = new HashSet<>();
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<StandingInstruction> due = instructions.findDue(today, PageRequest.of(0, BATCH_SIZE)).stream()
                    .filter(si -> !deferred.contains(si.getId()))
                    .toList();
            if (due.isEmpty()) {
                break;
            }
            for (StandingInstruction si : due) {
                switch (execute(si)) {
                    case SUCCEEDED -> succeeded++;
                    case FAILED -> failed++;
                    case DEFERRED -> deferred.add(si.getId());
                }
            }
        }
        return new RunSummary(succeeded, failed, deferred.size());
    }

    private Result execute(StandingInstruction si) {
        LocalDate runDate = si.getNextRunDate();
        String outcome;
        Result result = Result.FAILED;
        try {
            var transfer = core.transfer(
                    si.getUserId(),
                    si.idempotencyKeyFor(runDate),
                    si.getSourceAccountId(),
                    si.getTargetAccountNumber(),
                    si.getAmount(),
                    si.getCurrency(),
                    si.getRemarks());
            outcome = transfer.replayed() ? "OK (already executed)" : "OK";
            result = Result.SUCCEEDED;
        } catch (DependencyUnavailableException e) {
            log.warn("Standing instruction {} deferred, banking-core unavailable: {}", si.getId(), e.getMessage());
            return Result.DEFERRED;
        } catch (FincoreException e) {
            outcome = "FAILED: " + e.code();
        } catch (RuntimeException e) {
            log.error("Standing instruction {} failed unexpectedly", si.getId(), e);
            outcome = "FAILED: ERROR";
        }
        si.recordRun(outcome);
        instructions.save(si);
        return result;
    }

    private StandingInstruction require(UUID userId, UUID id) {
        return instructions.findByIdAndUserId(id, userId)
                .orElseThrow(() -> FincoreException.notFound("Standing instruction not found"));
    }
}
