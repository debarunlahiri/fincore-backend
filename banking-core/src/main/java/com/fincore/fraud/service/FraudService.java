package com.fincore.fraud.service;

import com.fincore.common.exception.FincoreException;
import com.fincore.fraud.rule.FraudRule;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Runs every registered {@link FraudRule}; adding a rule is just adding another @Component. */
@Service
public class FraudService {

    private final List<FraudRule> rules;

    public FraudService(List<FraudRule> rules) {
        this.rules = rules;
    }

    public void assertAllowed(FraudRule.TransferAttempt attempt) {
        List<String> reasons = rules.stream()
                .map(r -> r.evaluate(attempt))
                .flatMap(Optional::stream)
                .toList();
        if (!reasons.isEmpty()) {
            throw FincoreException.unprocessable("FRAUD_REJECTED", "Transfer rejected: " + String.join("; ", reasons));
        }
    }
}
