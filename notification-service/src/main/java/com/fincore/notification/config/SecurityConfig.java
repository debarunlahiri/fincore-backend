package com.fincore.notification.config;

import com.fincore.security.config.CommonSecurity;
import com.fincore.security.jwt.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The service exposes only actuator endpoints and the admin outbox/audit APIs; it is otherwise a
 * pure consumer.
 */
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
}
