package com.fincore.core.rule;

import com.fincore.core.config.FincoreProperties;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class HighValueRule implements FraudRule {

    private final FincoreProperties props;

    public HighValueRule(FincoreProperties props) {
        this.props = props;
    }

    @Override
    public Optional<String> evaluate(TransferAttempt attempt) {
        if (attempt.amount().compareTo(props.fraud().highValueThreshold()) > 0) {
            return Optional.of(
                    "Amount exceeds the single-transfer threshold of "
                            + props.fraud().highValueThreshold());
        }
        return Optional.empty();
    }
}
