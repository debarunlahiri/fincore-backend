package com.fincore.notification.mail;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Queues an email; the {@link EmailDispatcher} sends it. Call inside the caller's transaction. */
@Service
public class EmailService {

    private final EmailOutboxRepository outbox;

    public EmailService(EmailOutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Transactional
    public void queue(UUID eventId, String recipient, String subject, String body) {
        if (recipient == null || recipient.isBlank()) {
            return;
        }
        outbox.save(new EmailOutbox(eventId, recipient.trim(), subject, body));
    }
}
