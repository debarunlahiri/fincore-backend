package com.fincore.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.AbstractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Lockout, single active device, password change and the self-service security endpoints. */
class SecurityFlowIntegrationTest extends AbstractIntegrationTest {

    private static String uniqueName() {
        return "sec" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private void login(String user, String password, int expected) throws Exception {
        mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("username", user, "password", password))))
                .andExpect(status().is(expected));
    }

    private void refresh(String token, int expected) throws Exception {
        mvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("refreshToken", token))))
                .andExpect(status().is(expected));
    }

    @Test
    void fiveWrongPasswordsLockTheLoginEvenForTheCorrectPassword() throws Exception {
        String name = uniqueName();
        registerViaApi(name);

        for (int i = 0; i < 5; i++) {
            login(name, "wrong-password", 401);
        }

        mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("username", name, "password", "Passw0rd!23"))))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void aSuccessfulLoginResetsTheFailureCounter() throws Exception {
        String name = uniqueName();
        registerViaApi(name);

        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 4; i++) {
                login(name, "wrong-password", 401);
            }
            login(name, "Passw0rd!23", 200);
        }
    }

    @Test
    void signingInOnAnotherDeviceEndsTheFirstSession() throws Exception {
        String name = uniqueName();
        JsonNode firstDevice = registerViaApi(name);
        JsonNode secondDevice = loginViaApi(name, "Passw0rd!23");

        refresh(firstDevice.get("refreshToken").asText(), 401);
        refresh(secondDevice.get("refreshToken").asText(), 200);
    }

    @Test
    void theOldDeviceCannotKickOutTheNewOneByRetryingItsStaleToken() throws Exception {
        String name = uniqueName();
        JsonNode firstDevice = registerViaApi(name);
        JsonNode secondDevice = loginViaApi(name, "Passw0rd!23");

        refresh(firstDevice.get("refreshToken").asText(), 401);
        refresh(firstDevice.get("refreshToken").asText(), 401);

        refresh(secondDevice.get("refreshToken").asText(), 200);
    }

    @Test
    void changingThePasswordNeedsTheCurrentOneAndEndsEverySession() throws Exception {
        String name = uniqueName();
        JsonNode tokens = registerViaApi(name);

        mvc.perform(
                        post("/api/v1/security/change-password")
                                .header(HttpHeaders.AUTHORIZATION, bearer(tokens))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                Map.of(
                                                        "currentPassword",
                                                        "nope",
                                                        "newPassword",
                                                        "NewPassw0rd!45"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));

        mvc.perform(
                        post("/api/v1/security/change-password")
                                .header(HttpHeaders.AUTHORIZATION, bearer(tokens))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                Map.of(
                                                        "currentPassword",
                                                        "Passw0rd!23",
                                                        "newPassword",
                                                        "NewPassw0rd!45"))))
                .andExpect(status().isNoContent());

        refresh(tokens.get("refreshToken").asText(), 401);
        login(name, "Passw0rd!23", 401);
        login(name, "NewPassw0rd!45", 200);
    }

    @Test
    void loginHistoryAndSessionsNeverExposeInternalIds() throws Exception {
        String name = uniqueName();
        JsonNode tokens = registerViaApi(name);
        login(name, "wrong", 401);
        JsonNode second = loginViaApi(name, "Passw0rd!23");

        JsonNode history =
                body(
                        mvc.perform(
                                        get("/api/v1/security/login-history")
                                                .header(HttpHeaders.AUTHORIZATION, bearer(second)))
                                .andExpect(status().isOk())
                                .andReturn());
        JsonNode sessions =
                body(
                        mvc.perform(
                                        get("/api/v1/security/sessions")
                                                .header(HttpHeaders.AUTHORIZATION, bearer(second)))
                                .andExpect(status().isOk())
                                .andReturn());

        assertThat(history).hasSize(2);
        assertThat(history.get(0).has("id")).isFalse();
        assertThat(history.get(0).has("userId")).isFalse();
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).has("id")).isFalse();
        assertThat(tokens.get("accessToken").asText()).isNotBlank();
    }

    @Test
    void customerCanSignOutOfAllDevices() throws Exception {
        String name = uniqueName();
        JsonNode tokens = registerViaApi(name);

        mvc.perform(
                        delete("/api/v1/security/sessions")
                                .header(HttpHeaders.AUTHORIZATION, bearer(tokens)))
                .andExpect(status().isOk());

        refresh(tokens.get("refreshToken").asText(), 401);
    }

    @Test
    void securityEndpointsRequireAToken() throws Exception {
        mvc.perform(get("/api/v1/security/sessions")).andExpect(status().isUnauthorized());
    }
}
