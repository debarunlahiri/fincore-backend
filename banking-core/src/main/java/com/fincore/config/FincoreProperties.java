package com.fincore.config;

import java.math.BigDecimal;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fincore")
public record FincoreProperties(ZoneId zone, Account account, Fraud fraud) {

    public record Account(BigDecimal defaultDailyLimit) {}

    public record Fraud(BigDecimal highValueThreshold, int velocityMaxPerMinute) {}
}
