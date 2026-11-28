package com.fincore.notification.mail;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Sends due emails from the outbox over SMTP, retrying failures with backoff. */
@Component
@ConditionalOnProperty(name = "fincore.mail.enabled", havingValue = "true")
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    private final EmailOutboxRepository outbox;
    private final JavaMailSender mailSender;
    private final MailProperties props;

    public EmailDispatcher(
            EmailOutboxRepository outbox, JavaMailSender mailSender, MailProperties props) {
        this.outbox = outbox;
        this.mailSender = mailSender;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${fincore.mail.dispatch-delay:PT5S}")
    @Transactional
    public void dispatch() {
        List<EmailOutbox> due = outbox.findDue(Instant.now(), PageRequest.of(0, props.batchSize()));
        for (EmailOutbox email : due) {
            try {
                send(email);
                email.markSent();
                log.info("Email {} sent", email.getId());
            } catch (Exception e) {
                email.markFailed(e.getMessage(), props.maxAttempts());
                log.warn(
                        "Email {} failed (attempt {}/{}): {}",
                        email.getId(),
                        email.getAttempts(),
                        props.maxAttempts(),
                        e.getMessage());
            }
        }
    }

    private void send(EmailOutbox email) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
        helper.setFrom(new InternetAddress(props.fromAddress(), props.fromName(), "UTF-8"));
        helper.setTo(email.getRecipient());
        helper.setSubject(email.getSubject());
        helper.setText(email.getBody(), false);
        mailSender.send(message);
    }
}
