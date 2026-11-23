package com.fincore.scheduler.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.AbstractIntegrationTest;
import com.fincore.security.model.Role;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

public class StandingInstructionApiIntegrationTest extends AbstractIntegrationTest {

    private final UUID userId = UUID.randomUUID();
    private UUID source;
    private String targetNumber;

    private void accounts() {
        source = core.registerAccount(userId, "FC-A-" + UUID.randomUUID().toString().substring(0, 6));
        targetNumber = "FC-B-" + UUID.randomUUID().toString().substring(0, 6);
        core.registerAccount(UUID.randomUUID(), targetNumber);
    }

    private ResultActions create(String auth, String amount, String firstRun) throws Exception {
        return mvc.perform(post("/api/v1/standing-instructions")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "sourceAccountId", source.toString(),
                        "targetAccountNumber", targetNumber,
                        "amount", amount,
                        "frequency", "MONTHLY",
                        "firstRunDate", firstRun))));
    }

    private static String today() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
    }

    private String customer() {
        return bearer(userId, Role.CUSTOMER);
    }

    @Test
    void createListPauseResumeCancelLifecycle() throws Exception {
        accounts();
        JsonNode created = objectMapper.readTree(create(customer(), "500.00", today())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.frequency").value("MONTHLY"))
                .andReturn()
                .getResponse()
                .getContentAsString());
        String id = created.get("id").asText();

        mvc.perform(get("/api/v1/standing-instructions").header(HttpHeaders.AUTHORIZATION, customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/v1/standing-instructions/" + id + "/pause").header(HttpHeaders.AUTHORIZATION, customer()))
                .andExpect(jsonPath("$.status").value("PAUSED"));
        mvc.perform(post("/api/v1/standing-instructions/" + id + "/resume").header(HttpHeaders.AUTHORIZATION, customer()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(post("/api/v1/standing-instructions/" + id + "/cancel").header(HttpHeaders.AUTHORIZATION, customer()))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/v1/standing-instructions/" + id + "/resume").header(HttpHeaders.AUTHORIZATION, customer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSTRUCTION_CANCELLED"));
    }

    @Test
    void creatingOnSomeoneElsesAccountIs404() throws Exception {
        accounts();

        create(bearer(UUID.randomUUID(), Role.CUSTOMER), "500.00", today()).andExpect(status().isNotFound());
    }

    @Test
    void pastStartDateAndInvalidAmountAreRejected() throws Exception {
        accounts();

        create(customer(), "500.00", LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1).toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_START_DATE"));
        create(customer(), "0.00", today())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void anotherUserCannotControlMyInstruction() throws Exception {
        accounts();
        String id = objectMapper.readTree(create(customer(), "500.00", today()).andReturn().getResponse().getContentAsString())
                .get("id")
                .asText();

        mvc.perform(post("/api/v1/standing-instructions/" + id + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID(), Role.CUSTOMER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyAdminsCanTriggerTheJobAndCustomersCannot() throws Exception {
        mvc.perform(post("/api/v1/admin/standing-instructions/run").header(HttpHeaders.AUTHORIZATION, customer()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/standing-instructions/run")
                        .header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID(), Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").exists())
                .andExpect(jsonPath("$.deferred").exists());
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/standing-instructions")).andExpect(status().isUnauthorized());
    }
}
