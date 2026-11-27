package com.fincore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.security.jwt.JwtService;
import com.fincore.security.model.Role;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JwtService jwtService;

    protected String token(UUID userId, Role role) {
        return "Bearer "
                + jwtService.generateAccessToken(
                        userId, "user-" + userId.toString().substring(0, 6), role);
    }

    protected static String registeredEvent(UUID userId, String fullName, String dob) {
        return """
                {"userId":"%s","username":"u","email":"u@test.local","fullName":"%s","phone":"9876543210","dateOfBirth":"%s"}
                """
                .formatted(userId, fullName, dob);
    }
}
