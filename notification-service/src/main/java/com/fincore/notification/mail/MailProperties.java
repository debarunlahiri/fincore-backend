package com.fincore.notification.mail;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fincore.mail")
public record MailProperties(
        boolean enabled,
        String fromName,
        String fromAddress,
        int maxAttempts,
        Duration dispatchDelay,
        int batchSize) {}
