package com.fincore.transfer.service;

import com.fincore.common.exception.FincoreException;
import com.fincore.security.model.AuthenticatedUser;
import com.fincore.transfer.dto.TransferRequest;
import com.fincore.transfer.dto.TransferResponse;
import com.fincore.transfer.dto.TransferResult;
import com.fincore.transfer.repository.TransferRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Non-transactional facade. If two requests with the same Idempotency-Key race, the loser's transaction
 * fails on the unique constraint and rolls back completely (no double debit); here we then re-read and
 * return the winner's stored result.
 */
@Service
public class TransferService {

    private final TransferExecutor executor;
    private final TransferRepository transfers;

    TransferService(TransferExecutor executor, TransferRepository transfers) {
        this.executor = executor;
        this.transfers = transfers;
    }

    public TransferResult transfer(AuthenticatedUser user, String idempotencyKey, TransferRequest req) {
        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (key.isEmpty() || key.length() > 100) {
            throw FincoreException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header (1-100 chars) is required");
        }
        String hash = fingerprint(req);
        try {
            return executor.execute(user, key, req, hash);
        } catch (DataIntegrityViolationException race) {
            TransferResult winner = executor.replayFor(user, key, hash);
            if (winner == null) {
                throw race; // a different integrity problem, not an idempotency race
            }
            return winner;
        }
    }

    @Transactional(readOnly = true)
    public TransferResponse get(AuthenticatedUser user, UUID id) {
        return transfers.findByIdAndInitiatedBy(id, user.userId())
                .map(TransferResponse::from)
                .orElseThrow(() -> FincoreException.notFound("Transfer not found"));
    }

    private static String fingerprint(TransferRequest r) {
        String canonical = String.join("|",
                r.sourceAccountId().toString(),
                r.targetAccountNumber().trim(),
                r.amount().stripTrailingZeros().toPlainString(),
                r.currency() == null ? "INR" : r.currency().toUpperCase(),
                r.remarks() == null ? "" : r.remarks());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
