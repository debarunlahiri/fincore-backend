package com.fincore.outbox.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fincore.outbox.entity.OutboxEvent;
import com.fincore.outbox.entity.OutboxStatus;
import com.fincore.outbox.publisher.EventPublisher;
import com.fincore.outbox.repository.OutboxRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class OutboxRelayTest {

    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final OutboxRelay relay = new OutboxRelay(repository, publisher, 50, 3);

    private static OutboxEvent event() {
        return new OutboxEvent("TRANSFER", UUID.randomUUID(), "TransferCompleted", "{}");
    }

    @Test
    void successfulPublishMarksEventPublished() {
        OutboxEvent e = event();
        when(repository.claimPending(50)).thenReturn(List.of(e));

        int published = relay.relayBatch();

        assertThat(published).isEqualTo(1);
        assertThat(e.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(e.getPublishedAt()).isNotNull();
    }

    @Test
    void failedPublishKeepsEventPendingAndCountsTheAttempt() {
        OutboxEvent e = event();
        when(repository.claimPending(50)).thenReturn(List.of(e));
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(e);

        int published = relay.relayBatch();

        assertThat(published).isZero();
        assertThat(e.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(e.getAttempts()).isEqualTo(1);
        assertThat(e.getLastError()).contains("broker down");
    }

    @Test
    void eventBecomesFailedAfterTheMaximumNumberOfAttempts() {
        OutboxEvent e = event();
        when(repository.claimPending(50)).thenReturn(List.of(e));
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(e);

        relay.relayBatch();
        relay.relayBatch();
        relay.relayBatch();

        assertThat(e.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(e.getAttempts()).isEqualTo(3);
    }

    @Test
    void oneFailingEventDoesNotBlockTheRestOfTheBatch() {
        OutboxEvent bad = event();
        OutboxEvent good = event();
        when(repository.claimPending(50)).thenReturn(List.of(bad, good));
        doThrow(new IllegalStateException("poison")).when(publisher).publish(bad);

        int published = relay.relayBatch();

        assertThat(published).isEqualTo(1);
        assertThat(good.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void emptyBatchPublishesNothing() {
        when(repository.claimPending(50)).thenReturn(List.of());

        assertThat(relay.relayBatch()).isZero();
        verify(publisher, never()).publish(any());
    }

    @Test
    void replayResetsAFailedEventToPending() {
        OutboxEvent e = event();
        for (int i = 0; i < 3; i++) {
            e.markAttemptFailed("x", 3);
        }
        assertThat(e.getStatus()).isEqualTo(OutboxStatus.FAILED);

        e.resetForReplay();

        assertThat(e.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(e.getAttempts()).isZero();
        assertThat(e.getLastError()).isNull();
    }
}
