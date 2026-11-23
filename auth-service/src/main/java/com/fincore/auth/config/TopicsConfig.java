package com.fincore.auth.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "fincore.kafka.enabled", havingValue = "true")
public class TopicsConfig {

    /** Key = user id, so all events of one user stay ordered within a partition. */
    @Bean
    NewTopic userEventsTopic() {
        return TopicBuilder.name("fincore.user-events").partitions(3).replicas(1).build();
    }
}
