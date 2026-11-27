package com.fincore.core.rule;

import com.fincore.core.config.FincoreProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Velocity check: too many transfers from one account within a minute. Uses plain SQL on the
 * transfers table so the fraud module does not depend on the transfer module's classes (no package
 * cycle). Phase 3 moves the counter to Redis.
 */
@Component
public class VelocityRule implements FraudRule {

    private final JdbcClient jdbc;
    private final FincoreProperties props;

    public VelocityRule(JdbcClient jdbc, FincoreProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public Optional<String> evaluate(TransferAttempt attempt) {
        Integer recent =
                jdbc.sql(
                                "select count(*) from transfers where source_account_id = ? and created_at > ?")
                        .param(attempt.sourceAccountId())
                        .param(Timestamp.from(Instant.now().minusSeconds(60)))
                        .query(Integer.class)
                        .single();
        if (recent >= props.fraud().velocityMaxPerMinute()) {
            return Optional.of("Too many transfers in the last minute");
        }
        return Optional.empty();
    }
}
