package com.fincore.notification.mail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "email_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailOutbox {

    public enum Status {
        PENDING,
        SENT,
        FAILED
    }

    private static final Duration FIRST_DELAY = Duration.ofSeconds(30);
    private static final Duration MAX_DELAY = Duration.ofHours(1);

    @Id private UUID id = UUID.randomUUID();

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String body;

    @Column(nullable = false)
    private String status = Status.PENDING.name();

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;

    public EmailOutbox(UUID eventId, String recipient, String subject, String body) {
        this.eventId = eventId;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
    }

    public void markSent() {
        status = Status.SENT.name();
        sentAt = Instant.now();
        lastError = null;
    }

    /** Backoff of 30s, 1m, 2m, 4m ... capped at 1h; gives up after {@code maxAttempts}. */
    public void markFailed(String error, int maxAttempts) {
        attempts++;
        lastError = error == null ? null : error.substring(0, Math.min(error.length(), 300));
        if (attempts >= maxAttempts) {
            status = Status.FAILED.name();
            return;
        }
        Duration delay = FIRST_DELAY.multipliedBy(1L << Math.min(attempts - 1, 12));
        if (delay.compareTo(MAX_DELAY) > 0) {
            delay = MAX_DELAY;
        }
        nextAttemptAt = Instant.now().plus(delay);
    }
}
