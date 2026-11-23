package com.fincore.fraud.rule;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/** Strategy: each rule inspects a transfer attempt and returns a rejection reason, or empty to pass. */
public interface FraudRule {

    record TransferAttempt(UUID sourceAccountId, UUID targetAccountId, BigDecimal amount) {}

    Optional<String> evaluate(TransferAttempt attempt);
}
