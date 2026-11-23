package com.fincore.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.AbstractIntegrationTest;
import com.fincore.audit.repository.AuditRepository;
import com.fincore.auth.repository.UserRepository;
import com.fincore.security.jwt.JwtService;
import com.fincore.security.model.Role;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

public class AuthFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired AuditRepository auditRepository;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;

    private static String uniqueName() {
        return "u" + UUID.randomUUID().toString().substring(0, 10);
    }

    private void refresh(String refreshToken, int expectedStatus) throws Exception {
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", refreshToken))))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void registerThenLoginIssuesTokens() throws Exception {
        String name = uniqueName();
        JsonNode registered = registerViaApi(name);
        JsonNode loggedIn = loginViaApi(name, "Passw0rd!23");

        assertThat(registered.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(registered.get("expiresIn").asLong()).isPositive();
        assertThat(loggedIn.get("accessToken").asText()).isNotBlank();
        assertThat(loggedIn.get("refreshToken").asText()).isNotEqualTo(registered.get("refreshToken").asText());
    }

    @Test
    void issuedAccessTokenIsVerifiableByAnyServiceSharingTheSecret() throws Exception {
        String name = uniqueName();
        JsonNode registered = registerViaApi(name);

        var parsed = jwtService.parse(registered.get("accessToken").asText());

        assertThat(parsed).isPresent();
        assertThat(parsed.get().username()).isEqualTo(name);
        assertThat(parsed.get().role()).isEqualTo(Role.CUSTOMER);
    }

    @Test
    void registrationWritesAUserRegisteredEventToTheOutboxInTheSameTransaction() throws Exception {
        String name = uniqueName();
        registerViaApi(name);
        UUID userId = users.findByUsername(name).orElseThrow().getId();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select event_type, status, payload from outbox_events where aggregate_id = ?", userId);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("event_type")).isEqualTo("UserRegistered");
        assertThat(rows.get(0).get("status")).isEqualTo("PENDING");
        JsonNode payload = objectMapper.readTree((String) rows.get(0).get("payload"));
        assertThat(payload.get("userId").asText()).isEqualTo(userId.toString());
        assertThat(payload.get("fullName").asText()).isEqualTo("Test " + name);
        assertThat(payload.get("dateOfBirth").asText()).isEqualTo("1990-01-01");
    }

    @Test
    void failedRegistrationWritesNoOutboxEvent() throws Exception {
        String name = uniqueName();
        registerViaApi(name);
        long before = jdbc.queryForObject("select count(*) from outbox_events", Long.class);

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", name,
                                "email", "other-" + name + "@test.local",
                                "password", "Passw0rd!23",
                                "fullName", "Dup",
                                "phone", "9876543210",
                                "dateOfBirth", "1990-01-01"))))
                .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("select count(*) from outbox_events", Long.class)).isEqualTo(before);
    }

    @Test
    void duplicateUsernameIsRejectedWithConflict() throws Exception {
        String name = uniqueName();
        registerViaApi(name);

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", name,
                                "email", "other-" + name + "@test.local",
                                "password", "Passw0rd!23",
                                "fullName", "Dup",
                                "phone", "9876543210",
                                "dateOfBirth", "1990-01-01"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
    }

    @Test
    void invalidRegistrationReturnsFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", "x",
                                "email", "not-an-email",
                                "password", "short",
                                "fullName", "",
                                "phone", "abc",
                                "dateOfBirth", "1990-01-01"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.username").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists())
                .andExpect(jsonPath("$.errors.phone").exists());
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameGenericAnswer() throws Exception {
        String name = uniqueName();
        registerViaApi(name);

        var wrongPassword = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", name, "password", "WrongPassword1"))))
                .andExpect(status().isUnauthorized())
                .andReturn();
        var unknownUser = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "nobody-" + name, "password", "WrongPassword1"))))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(body(wrongPassword).get("detail")).isEqualTo(body(unknownUser).get("detail"));
    }

    @Test
    void failedAndSuccessfulLoginsAreAudited() throws Exception {
        String name = uniqueName();
        registerViaApi(name);
        mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", name, "password", "WrongPassword1"))));
        loginViaApi(name, "Passw0rd!23");

        var actions = auditRepository.findByActorOrderByCreatedAtDesc(name, PageRequest.of(0, 10)).stream()
                .map(e -> e.getAction())
                .toList();

        assertThat(actions).contains("LOGIN_FAILED", "LOGIN_SUCCEEDED");
    }

    @Test
    void refreshTokenRotatesAndTheOldOneStopsWorking() throws Exception {
        JsonNode first = registerViaApi(uniqueName());
        String oldRefresh = first.get("refreshToken").asText();

        var rotated = mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", oldRefresh))))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(body(rotated).get("refreshToken").asText()).isNotEqualTo(oldRefresh);
        refresh(oldRefresh, 401);
    }

    @Test
    void reusingARevokedRefreshTokenRevokesEveryActiveSession() throws Exception {
        JsonNode first = registerViaApi(uniqueName());
        String stolen = first.get("refreshToken").asText();

        var rotated = mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", stolen))))
                .andExpect(status().isOk())
                .andReturn();
        String legitimate = body(rotated).get("refreshToken").asText();

        refresh(stolen, 401); // replay of the old token => theft suspected
        refresh(legitimate, 401); // so the legitimate session is revoked too
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        JsonNode tokens = registerViaApi(uniqueName());
        String refreshToken = tokens.get("refreshToken").asText();

        mvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isNoContent());

        refresh(refreshToken, 401);
    }

    @Test
    void protectedEndpointsRequireAValidToken() throws Exception {
        mvc.perform(get("/api/v1/admin/audit")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/audit").header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerCannotReadTheAuditLogButAdminCan() throws Exception {
        JsonNode customer = registerViaApi(uniqueName());

        mvc.perform(get("/api/v1/admin/audit").header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/admin/audit").header(HttpHeaders.AUTHORIZATION, bearer(adminTokens())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void internalEndpointsAreUnreachableWithAUserToken() throws Exception {
        JsonNode customer = registerViaApi(uniqueName());

        mvc.perform(get("/internal/anything").header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/anything")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
