package com.fincore;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One shared PostgreSQL container per JVM so Spring reuses a single application context across test
 * classes.
 */
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

    protected String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected static String bearer(JsonNode tokens) {
        return "Bearer " + tokens.get("accessToken").asText();
    }

    protected JsonNode registerViaApi(String username) throws Exception {
        var request =
                Map.of(
                        "username",
                        username,
                        "email",
                        username + "@test.local",
                        "password",
                        "Passw0rd!23",
                        "fullName",
                        "Test " + username,
                        "phone",
                        "9876543210",
                        "dateOfBirth",
                        "1990-01-01");
        return body(
                mvc.perform(
                                post("/api/v1/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json(request)))
                        .andExpect(status().isCreated())
                        .andReturn());
    }

    protected JsonNode loginViaApi(String username, String password) throws Exception {
        return body(
                mvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                json(
                                                        Map.of(
                                                                "username",
                                                                username,
                                                                "password",
                                                                password))))
                        .andExpect(status().isOk())
                        .andReturn());
    }

    protected JsonNode adminTokens() throws Exception {
        return loginViaApi("admin", "TestAdmin!123");
    }
}
