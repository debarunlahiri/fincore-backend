package com.fincore.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.messaging.service.ProcessedEvents;
import com.fincore.notification.entity.Notification;
import com.fincore.notification.repository.NotificationRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class NotificationServiceTest {

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final ProcessedEvents processed = mock(ProcessedEvents.class);
    private final NotificationService service =
            new NotificationService(notifications, processed, new ObjectMapper());

    private static String transferPayload(UUID source, UUID target) {
        return """
                {"transferId":"%s","sourceAccountId":"%s","targetAccountId":"%s","amount":5000.00,"currency":"INR"}
                """
                .formatted(UUID.randomUUID(), source, target);
    }

    private static String paymentPayload(UUID account) {
        return """
                {"paymentId":"%s","accountId":"%s","rail":"UPI","amount":250.00,"currency":"INR","status":"SETTLED"}
                """
                .formatted(UUID.randomUUID(), account);
    }

    private void firstDelivery(UUID eventId) {
        when(processed.markProcessed(NotificationService.CONSUMER_NAME, eventId)).thenReturn(true);
    }

    @Test
    void firstDeliveryOfATransferSendsDebitAndCreditAlerts() {
        UUID eventId = UUID.randomUUID();
        firstDelivery(eventId);
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();

        service.handleTransferCompleted(eventId, transferPayload(source, target));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Notification::getAccountId)
                .containsExactlyInAnyOrder(source, target);
        assertThat(captor.getAllValues())
                .extracting(Notification::getMessage)
                .anyMatch(m -> m.startsWith("Debit alert"))
                .anyMatch(m -> m.startsWith("Credit alert"));
    }

    @Test
    void redeliveredTransferEventIsSkipped() {
        UUID eventId = UUID.randomUUID();
        when(processed.markProcessed(NotificationService.CONSUMER_NAME, eventId)).thenReturn(false);

        service.handleTransferCompleted(
                eventId, transferPayload(UUID.randomUUID(), UUID.randomUUID()));

        verify(notifications, never()).save(any(Notification.class));
    }

    @Test
    void malformedPayloadThrowsSoItCanBeRetriedAndDeadLettered() {
        UUID eventId = UUID.randomUUID();
        firstDelivery(eventId);

        assertThatThrownBy(() -> service.handleTransferCompleted(eventId, "{not json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.handleTransferCompleted(eventId, "{\"amount\":1}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settledPaymentProducesACompletionMessage() {
        UUID eventId = UUID.randomUUID();
        firstDelivery(eventId);

        service.handlePaymentEvent(eventId, "PaymentSettled", paymentPayload(UUID.randomUUID()));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(captor.capture());
        assertThat(captor.getValue().getMessage())
                .contains("UPI")
                .contains("250.00 INR")
                .contains("completed");
    }

    @Test
    void refundedAndFailedPaymentsTellTheCustomerWhatHappenedToTheirMoney() {
        UUID refunded = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        firstDelivery(refunded);
        firstDelivery(failed);

        service.handlePaymentEvent(refunded, "PaymentRefunded", paymentPayload(UUID.randomUUID()));
        service.handlePaymentEvent(failed, "PaymentFailed", paymentPayload(UUID.randomUUID()));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notifications, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getMessage()).contains("refunded");
        assertThat(captor.getAllValues().get(1).getMessage()).contains("no money was debited");
    }

    @Test
    void internalOnlyPaymentEventsAreMarkedProcessedButNotNotified() {
        UUID eventId = UUID.randomUUID();
        firstDelivery(eventId);

        service.handlePaymentEvent(eventId, "PaymentInitiated", paymentPayload(UUID.randomUUID()));

        verify(processed).markProcessed(NotificationService.CONSUMER_NAME, eventId);
        verify(notifications, never()).save(any(Notification.class));
    }

    @Test
    void redeliveredPaymentEventIsSkipped() {
        UUID eventId = UUID.randomUUID();
        when(processed.markProcessed(NotificationService.CONSUMER_NAME, eventId)).thenReturn(false);

        service.handlePaymentEvent(eventId, "PaymentSettled", paymentPayload(UUID.randomUUID()));

        verify(notifications, never()).save(any(Notification.class));
    }
}
