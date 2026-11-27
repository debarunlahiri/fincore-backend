package com.fincore.core.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fincore.common.exception.FincoreException;
import com.fincore.core.config.FincoreProperties;
import com.fincore.core.service.FraudService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class FraudRulesTest {

    private static FraudRule.TransferAttempt attempt(String amount) {
        return new FraudRule.TransferAttempt(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(amount));
    }

    private static HighValueRule highValueRule() {
        var fraud = new FincoreProperties.Fraud(new BigDecimal("200000"), 5);
        return new HighValueRule(new FincoreProperties(null, null, fraud));
    }

    @Test
    void amountAtTheThresholdPassesButAboveItIsFlagged() {
        HighValueRule rule = highValueRule();

        assertThat(rule.evaluate(attempt("200000.00"))).isEmpty();
        assertThat(rule.evaluate(attempt("200000.01"))).isPresent();
    }

    @Test
    void serviceAllowsWhenNoRuleObjects() {
        FraudService service =
                new FraudService(List.of(a -> Optional.empty(), a -> Optional.empty()));

        assertThatCode(() -> service.assertAllowed(attempt("10"))).doesNotThrowAnyException();
    }

    @Test
    void serviceRejectsAndReportsEveryReason() {
        FraudService service =
                new FraudService(
                        List.of(
                                a -> Optional.of("reason one"),
                                a -> Optional.empty(),
                                a -> Optional.of("reason two")));

        assertThatThrownBy(() -> service.assertAllowed(attempt("10")))
                .isInstanceOf(FincoreException.class)
                .hasMessageContaining("reason one")
                .hasMessageContaining("reason two")
                .satisfies(
                        e -> assertThat(((FincoreException) e).code()).isEqualTo("FRAUD_REJECTED"));
    }

    @Test
    void serviceWithNoRulesAllowsEverything() {
        assertThatCode(() -> new FraudService(List.of()).assertAllowed(attempt("999999999")))
                .doesNotThrowAnyException();
    }
}
