package com.fincore.payment.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.AbstractIntegrationTest;
import com.fincore.security.model.Role;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

public class PaymentApiIntegrationTest extends AbstractIntegrationTest {

    private final UUID userId = UUID.randomUUID();
    private final String accountNumber = "FC1000000001";

    private ResultActions pay(String auth, String beneficiary, String amount, String key)
            throws Exception {
        var request =
                post("/api/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                json(
                                        Map.of(
                                                "accountNumber", accountNumber,
                                                "rail", "UPI",
                                                "beneficiaryRef", beneficiary,
                                                "amount", amount)));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private String customer() {
        return bearer(userId, Role.CUSTOMER);
    }

    @Test
    void settledPaymentReturns201() throws Exception {
        pay(customer(), "shop@upi", "100.00", UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.rail").value("UPI"));
    }

    @Test
    void declinedPaymentIsReportedAsRefunded() throws Exception {
        pay(customer(), "FAIL-account", "100.00", UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.failureReason").value("Declined by beneficiary bank"));
    }

    @Test
    void unknownBankOutcomeReturns202AndTheStatusCanBePolled() throws Exception {
        JsonNode body =
                objectMapper.readTree(
                        pay(customer(), "TIMEOUT-account", "100.00", UUID.randomUUID().toString())
                                .andExpect(status().isAccepted())
                                .andExpect(jsonPath("$.status").value("DEBITED"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        mvc.perform(
                        get("/api/v1/payments/" + body.get("reference").asText())
                                .header(HttpHeaders.AUTHORIZATION, customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEBITED"));
    }

    @Test
    void ledgerRejectionIsVisibleAsAFailedPayment() throws Exception {
        ledger.rejectDebitsWith("INSUFFICIENT_FUNDS");

        pay(customer(), "shop@upi", "100.00", UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void ledgerOutageReturns202WithARequestedPayment() throws Exception {
        ledger.setDown(true);

        pay(customer(), "shop@upi", "100.00", UUID.randomUUID().toString())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REQUESTED"));
    }

    @Test
    void replayedRequestReturns200WithTheReplayHeader() throws Exception {
        String key = UUID.randomUUID().toString();

        pay(customer(), "shop@upi", "100.00", key).andExpect(status().isCreated());
        pay(customer(), "shop@upi", "100.00", key)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void sameKeyWithDifferentPayloadIs422() throws Exception {
        String key = UUID.randomUUID().toString();
        pay(customer(), "shop@upi", "100.00", key).andExpect(status().isCreated());

        pay(customer(), "shop@upi", "200.00", key)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void validationErrorsAreReportedPerField() throws Exception {
        mvcPost(
                        customer(),
                        Map.of(
                                "accountNumber",
                                accountNumber,
                                "rail",
                                "UPI",
                                "beneficiaryRef",
                                "",
                                "amount",
                                "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        pay(customer(), "shop@upi", "100.00", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void paymentsAreOnlyVisibleToTheirInitiator() throws Exception {
        JsonNode body =
                objectMapper.readTree(
                        pay(customer(), "shop@upi", "100.00", UUID.randomUUID().toString())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        String other = bearer(UUID.randomUUID(), Role.CUSTOMER);

        mvc.perform(
                        get("/api/v1/payments/" + body.get("reference").asText())
                                .header(HttpHeaders.AUTHORIZATION, other))
                .andExpect(status().isNotFound());
    }

    @Test
    void authenticationAndRolesAreEnforced() throws Exception {
        pay("Bearer garbage", "shop@upi", "100.00", UUID.randomUUID().toString())
                .andExpect(status().isUnauthorized());
        pay(
                        bearer(UUID.randomUUID(), Role.ADMIN),
                        "shop@upi",
                        "100.00",
                        UUID.randomUUID().toString())
                .andExpect(status().isForbidden());
    }

    private ResultActions mvcPost(String auth, Map<String, String> body) throws Exception {
        return mvc.perform(
                post("/api/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)));
    }
}
