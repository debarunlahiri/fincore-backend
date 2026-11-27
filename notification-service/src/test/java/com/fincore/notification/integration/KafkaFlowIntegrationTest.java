package com.fincore.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fincore.notification.repository.NotificationRepository;
import com.fincore.outbox.entity.OutboxEvent;
import com.fincore.outbox.entity.OutboxStatus;
import com.fincore.outbox.publisher.KafkaEventPublisher;
import com.fincore.outbox.relay.OutboxRelay;
import com.fincore.outbox.repository.OutboxRepository;
import com.fincore.outbox.service.OutboxService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The whole asynchronous path against a real broker: outbox row -> relay -> topic -> listener ->
 * notifications, plus duplicate delivery and the dead-letter topic.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "fincore.kafka.enabled=true")
public class KafkaFlowIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired OutboxService outboxService;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxRelay relay;
    @Autowired TransactionTemplate tx;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired NotificationRepository notifications;

    private static Map<String, Object> transferPayload() {
        return Map.of(
                "transferId", UUID.randomUUID().toString(),
                "sourceAccountId", UUID.randomUUID().toString(),
                "targetAccountId", UUID.randomUUID().toString(),
                "amount", 100.00,
                "currency", "INR");
    }

    private OutboxEvent recordTransferCompleted() {
        return tx.execute(
                status ->
                        outboxService.record(
                                "Transfer",
                                UUID.randomUUID(),
                                "TransferCompleted",
                                transferPayload()));
    }

    @Test
    void outboxEventTravelsThroughKafkaAndIsNotifiedOnce() {
        OutboxEvent event = recordTransferCompleted();

        relay.relayBatch();

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> assertThat(notifications.countByEventId(event.getId())).isEqualTo(2));
        assertThat(outbox.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void duplicateDeliveryOfTheSameEventNotifiesOnlyOnce() throws Exception {
        OutboxEvent event = recordTransferCompleted();
        relay.relayBatch();
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> assertThat(notifications.countByEventId(event.getId())).isEqualTo(2));

        // a relay crash before commit re-sends the same event id: simulate the redelivery
        kafka.send(
                        withHeaders(
                                KafkaEventPublisher.topicFor("Transfer"),
                                event.getId(),
                                "TransferCompleted",
                                event.getPayload()))
                .get();
        // a later, different event proves the listener processed past the duplicate
        OutboxEvent marker = recordTransferCompleted();
        relay.relayBatch();

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () ->
                                assertThat(notifications.countByEventId(marker.getId()))
                                        .isEqualTo(2));
        assertThat(notifications.countByEventId(event.getId())).isEqualTo(2);
    }

    @Test
    void poisonMessageIsParkedOnTheDeadLetterTopic() {
        String key = "poison-" + UUID.randomUUID();
        // no eventId / eventType headers: the listener rejects it, retries, then the recoverer
        // publishes to .DLT
        kafka.send(KafkaEventPublisher.topicFor("Transfer"), key, "{}");

        try (KafkaConsumer<String, String> dlt =
                dltConsumer(KafkaEventPublisher.topicFor("Transfer") + ".DLT")) {
            await().atMost(Duration.ofSeconds(60))
                    .untilAsserted(
                            () -> {
                                List<String> keys = new java.util.ArrayList<>();
                                for (ConsumerRecord<String, String> r :
                                        dlt.poll(Duration.ofMillis(500))) {
                                    keys.add(r.key());
                                }
                                assertThat(keys).contains(key);
                            });
        }
    }

    private static ProducerRecord<String, String> withHeaders(
            String topic, UUID eventId, String eventType, String payload) {
        var record =
                new ProducerRecord<String, String>(topic, UUID.randomUUID().toString(), payload);
        record.headers()
                .add(
                        KafkaEventPublisher.EVENT_ID_HEADER,
                        eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers()
                .add(
                        KafkaEventPublisher.EVENT_TYPE_HEADER,
                        eventType.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private static KafkaConsumer<String, String> dltConsumer(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        var consumer = new KafkaConsumer<String, String>(props);
        consumer.subscribe(List.of(topic));
        return consumer;
    }
}
