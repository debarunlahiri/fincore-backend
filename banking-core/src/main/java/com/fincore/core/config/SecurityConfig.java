package com.fincore.core.config;

import com.fincore.security.config.CommonSecurity;
import com.fincore.security.jwt.JwtService;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtService jwtService,
            @Value("${fincore.internal.api-key}") String internalKey)
            throws Exception {
        return CommonSecurity.statelessApi(http, jwtService, internalKey, a -> {}).build();
    }

    @Configuration
    @ConditionalOnProperty(name = "fincore.kafka.enabled", havingValue = "true")
    static class Topics {

        @Bean
        NewTopic transferEventsTopic() {
            return TopicBuilder.name("fincore.transfer-events").partitions(3).replicas(1).build();
        }

        @Bean
        NewTopic accountEventsTopic() {
            return TopicBuilder.name("fincore.account-events").partitions(3).replicas(1).build();
        }
    }
}
