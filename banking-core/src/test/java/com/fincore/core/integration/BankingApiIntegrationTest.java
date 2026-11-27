package com.fincore.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fincore.AbstractIntegrationTest;
import com.fincore.common.exception.DependencyUnavailableException;
import com.fincore.core.client.CustomerClient;
import com.fincore.security.model.Role;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * End-to-end through HTTP, security and validation, exactly as a client holding a JWT would use the
 * API.
 */
class BankingApiIntegrationTest extends AbstractIntegrationTest {

    /** accountId is kept as an alias: the public API now addresses accounts by number only. */
    private record Client(String auth, String accountId, String accountNumber) {}

    private Client onboard() throws Exception {
        String auth = bearer(UUID.randomUUID(), Role.CUSTOMER);
        JsonNode account =
                objectMapper.readTree(
                        mvc.perform(
                                        post("/api/v1/accounts")
                                                .header(HttpHeaders.AUTHORIZATION, auth)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(json(Map.of("type", "SAVINGS"))))
                                .andExpect(status().isCreated())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());
        return new Client(
                auth, account.get("accountNumber").asText(), account.get("accountNumber").asText());
    }

    private void deposit(Client c, String amount) throws Exception {
        mvc.perform(
                        post("/api/v1/accounts/" + c.accountId() + "/deposits")
                                .header(HttpHeaders.AUTHORIZATION, c.auth())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("amount", amount))))
                .andExpect(status().isOk());
    }

    private ResultActions transfer(Client from, Client to, String amount, String key)
            throws Exception {
        var request =
                post("/api/v1/transfers")
                        .header(HttpHeaders.AUTHORIZATION, from.auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                json(
                                        Map.of(
                                                "sourceAccountNumber", from.accountNumber(),
                                                "targetAccountNumber", to.accountNumber(),
                                                "amount", amount)));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    @Test
    void fullCustomerJourneyEndsWithConsistentBooks() throws Exception {
        Client alice = onboard();
        Client bob = onboard();
        deposit(alice, "20000.00");
        deposit(bob, "10000.00");

        transfer(alice, bob, "5000.00", UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount").value(5000.00));

        mvc.perform(
                        get("/api/v1/accounts/" + alice.accountId())
                                .header(HttpHeaders.AUTHORIZATION, alice.auth()))
                .andExpect(jsonPath("$.balance").value(15000.00));
        mvc.perform(
                        get("/api/v1/accounts/" + bob.accountId())
                                .header(HttpHeaders.AUTHORIZATION, bob.auth()))
                .andExpect(jsonPath("$.balance").value(15000.00));

        mvc.perform(
                        get("/api/v1/admin/reconciliation")
                                .header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true))
                .andExpect(jsonPath("$.balanceMismatches").isEmpty())
                .andExpect(jsonPath("$.unbalancedJournals").isEmpty());
    }

    @Test
    void replayedTransferReturns200WithTheReplayHeader() throws Exception {
        Client alice = onboard();
        Client bob = onboard();
        deposit(alice, "1000.00");
        String key = UUID.randomUUID().toString();

        transfer(alice, bob, "100.00", key).andExpect(status().isCreated());
        transfer(alice, bob, "100.00", key)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        mvc.perform(
                        get("/api/v1/accounts/" + alice.accountId())
                                .header(HttpHeaders.AUTHORIZATION, alice.auth()))
                .andExpect(jsonPath("$.balance").value(900.00));
    }

    @Test
    void transferWithoutIdempotencyKeyIsABadRequest() throws Exception {
        Client alice = onboard();
        Client bob = onboard();
        deposit(alice, "1000.00");

        transfer(alice, bob, "100.00", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void insufficientFundsIs422WithAnErrorCode() throws Exception {
        Client alice = onboard();
        Client bob = onboard();

        transfer(alice, bob, "100.00", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void invalidAmountsAreRejectedByValidation() throws Exception {
        Client alice = onboard();
        Client bob = onboard();

        transfer(alice, bob, "0.00", UUID.randomUUID().toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());
        transfer(alice, bob, "10.999", UUID.randomUUID().toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void accountOpeningRequiresVerifiedKyc() throws Exception {
        when(customerClient.byUser(any()))
                .thenReturn(
                        new CustomerClient.CustomerSummary(
                                UUID.randomUUID(), UUID.randomUUID(), "Test Customer", "PENDING"));

        mvc.perform(
                        post("/api/v1/accounts")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(UUID.randomUUID(), Role.CUSTOMER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("type", "CURRENT"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("KYC_REQUIRED"));
    }

    @Test
    void accountOpeningFailsClearlyWhenTheCustomerServiceIsDown() throws Exception {
        when(customerClient.byUser(any()))
                .thenThrow(new DependencyUnavailableException("customer-service unreachable"));

        mvc.perform(
                        post("/api/v1/accounts")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(UUID.randomUUID(), Role.CUSTOMER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("type", "SAVINGS"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void systemAccountsCannotBeOpenedByCustomers() throws Exception {
        mvc.perform(
                        post("/api/v1/accounts")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(UUID.randomUUID(), Role.CUSTOMER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("type", "SYSTEM"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ACCOUNT_TYPE"));
    }

    @Test
    void customersCannotSeeEachOthersAccountsOrStatements() throws Exception {
        Client alice = onboard();
        Client mallory = onboard();

        mvc.perform(
                        get("/api/v1/accounts/" + alice.accountId())
                                .header(HttpHeaders.AUTHORIZATION, mallory.auth()))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get("/api/v1/accounts/" + alice.accountId() + "/statement")
                                .header(HttpHeaders.AUTHORIZATION, mallory.auth()))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/v1/accounts/" + alice.accountId() + "/withdrawals")
                                .header(HttpHeaders.AUTHORIZATION, mallory.auth())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("amount", "1.00"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void listingAccountsReturnsOnlyTheCallersAccounts() throws Exception {
        Client alice = onboard();
        onboard();

        mvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, alice.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].accountNumber").value(alice.accountNumber()));
    }

    @Test
    void statementIsPagedNewestFirstAndRunningBalanceIsConsistent() throws Exception {
        Client alice = onboard();
        deposit(alice, "100.00");
        deposit(alice, "50.00");
        mvc.perform(
                        post("/api/v1/accounts/" + alice.accountId() + "/withdrawals")
                                .header(HttpHeaders.AUTHORIZATION, alice.auth())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("amount", "30.00"))))
                .andExpect(status().isOk());

        JsonNode page =
                objectMapper.readTree(
                        mvc.perform(
                                        get("/api/v1/accounts/"
                                                        + alice.accountId()
                                                        + "/statement?page=0&size=2")
                                                .header(HttpHeaders.AUTHORIZATION, alice.auth()))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        assertThat(page.get("totalElements").asInt()).isEqualTo(3);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page.get("content")).hasSize(2);
        assertThat(page.get("content").get(0).get("direction").asText()).isEqualTo("DEBIT");
        assertThat(page.get("content").get(0).get("balanceAfter").decimalValue())
                .isEqualByComparingTo("120");
    }

    @Test
    void csvStatementContainsHeaderAndRowsAndValidatesTheRange() throws Exception {
        Client alice = onboard();
        deposit(alice, "100.00");
        String today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();

        String csv =
                mvc.perform(
                                get("/api/v1/accounts/"
                                                + alice.accountId()
                                                + "/statement.csv?from="
                                                + today
                                                + "&to="
                                                + today)
                                        .header(HttpHeaders.AUTHORIZATION, alice.auth()))
                        .andExpect(status().isOk())
                        .andExpect(
                                header().string(
                                                HttpHeaders.CONTENT_DISPOSITION,
                                                containsString("attachment")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(csv).startsWith("date,journal_id,direction,amount,currency,balance_after");
        assertThat(csv.lines().count()).isEqualTo(2);
        assertThat(csv).contains("CREDIT").contains("100.0000");

        mvc.perform(
                        get("/api/v1/accounts/"
                                        + alice.accountId()
                                        + "/statement.csv?from=2026-02-01&to=2026-01-01")
                                .header(HttpHeaders.AUTHORIZATION, alice.auth()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RANGE"));
    }

    @Test
    void protectedEndpointsRequireAValidToken() throws Exception {
        mvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roleBasedAccessIsEnforced() throws Exception {
        mvc.perform(
                        get("/api/v1/admin/reconciliation")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(UUID.randomUUID(), Role.CUSTOMER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/accounts").header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void malformedJsonIsABadRequestNotAServerError() throws Exception {
        mvc.perform(
                        post("/api/v1/accounts")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(UUID.randomUUID(), Role.CUSTOMER))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{broken"))
                .andExpect(status().isBadRequest());
    }
}
