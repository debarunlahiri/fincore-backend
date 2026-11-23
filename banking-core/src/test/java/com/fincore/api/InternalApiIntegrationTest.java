package com.fincore.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fincore.AbstractIntegrationTest;
import com.fincore.security.filter.InternalApiKeyFilter;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** The /internal API used by the payment-service and scheduler-service. */
class InternalApiIntegrationTest extends AbstractIntegrationTest {

    @Value("${fincore.internal.api-key}") String key;

    private static Map<String, Object> debitBody(UUID paymentId, TestCustomer c, String amount) {
        return Map.of(
                "paymentId", paymentId.toString(),
                "userId", c.user().userId().toString(),
                "accountId", c.account().getId().toString(),
                "amount", amount);
    }

    @Test
    void internalEndpointsRejectCallersWithoutTheKey() throws Exception {
        TestCustomer c = newCustomer(new BigDecimal("100"));
        String body = json(debitBody(UUID.randomUUID(), c, "10.00"));

        mvc.perform(post("/internal/ledger/payment-debits").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/ledger/payment-debits")
                        .header(InternalApiKeyFilter.HEADER, "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/ledger/payment-debits")
                        .header(HttpHeaders.AUTHORIZATION, bearer(c))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        assertThat(balanceOf(c.account())).isEqualByComparingTo("100");
    }

    @Test
    void paymentDebitAndReversalRoundTripOverHttp() throws Exception {
        TestCustomer c = newCustomer(new BigDecimal("500"));
        UUID paymentId = UUID.randomUUID();

        mvc.perform(post("/internal/ledger/payment-debits")
                        .header(InternalApiKeyFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(debitBody(paymentId, c, "200.00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.journalId").exists());
        assertThat(balanceOf(c.account())).isEqualByComparingTo("300");

        mvc.perform(post("/internal/ledger/payment-reversals")
                        .header(InternalApiKeyFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("paymentId", paymentId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false));
        assertThat(balanceOf(c.account())).isEqualByComparingTo("500");
    }

    @Test
    void businessRejectionsKeepTheirStatusAndCodeForTheCaller() throws Exception {
        TestCustomer c = newCustomer(new BigDecimal("10"));

        mvc.perform(post("/internal/ledger/payment-debits")
                        .header(InternalApiKeyFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(debitBody(UUID.randomUUID(), c, "100.00"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void invalidInternalRequestIsRejectedByValidation() throws Exception {
        mvc.perform(post("/internal/ledger/payment-debits")
                        .header(InternalApiKeyFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("paymentId", UUID.randomUUID().toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void ownershipAndAccountNumberLookups() throws Exception {
        TestCustomer c = newCustomer(BigDecimal.ZERO);
        TestCustomer other = newCustomer(BigDecimal.ZERO);

        mvc.perform(get("/internal/accounts/" + c.account().getId() + "/owner/" + c.user().userId())
                        .header(InternalApiKeyFilter.HEADER, key))
                .andExpect(status().isNoContent());
        mvc.perform(get("/internal/accounts/" + c.account().getId() + "/owner/" + other.user().userId())
                        .header(InternalApiKeyFilter.HEADER, key))
                .andExpect(status().isNotFound());
        mvc.perform(get("/internal/accounts/by-number/" + c.account().getAccountNumber())
                        .header(InternalApiKeyFilter.HEADER, key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(c.account().getId().toString()));
        mvc.perform(get("/internal/accounts/by-number/FC0000000000").header(InternalApiKeyFilter.HEADER, key))
                .andExpect(status().isNotFound());
    }

    @Test
    void internalTransferRunsThroughTheNormalTransferPathAndIsIdempotent() throws Exception {
        TestCustomer payer = newCustomer(new BigDecimal("1000"));
        TestCustomer payee = newCustomer(BigDecimal.ZERO);
        Map<String, Object> request = Map.of(
                "userId", payer.user().userId().toString(),
                "idempotencyKey", "SI-test-" + UUID.randomUUID(),
                "request", Map.of(
                        "sourceAccountId", payer.account().getId().toString(),
                        "targetAccountNumber", payee.account().getAccountNumber(),
                        "amount", "100.00"));

        mvc.perform(post("/internal/transfers")
                        .header(InternalApiKeyFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.transfer.status").value("COMPLETED"));
        mvc.perform(post("/internal/transfers")
                        .header(InternalApiKeyFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));

        assertThat(balanceOf(payer.account())).isEqualByComparingTo("900");
        assertThat(balanceOf(payee.account())).isEqualByComparingTo("100");
    }

    @Test
    void internalTransferStillEnforcesOwnership() throws Exception {
        TestCustomer owner = newCustomer(new BigDecimal("1000"));
        TestCustomer payee = newCustomer(BigDecimal.ZERO);
        TestCustomer stranger = newCustomer(BigDecimal.ZERO);

        mvc.perform(post("/internal/transfers")
                        .header(InternalApiKeyFilter.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "userId", stranger.user().userId().toString(),
                                "idempotencyKey", "k-" + UUID.randomUUID(),
                                "request", Map.of(
                                        "sourceAccountId", owner.account().getId().toString(),
                                        "targetAccountNumber", payee.account().getAccountNumber(),
                                        "amount", "10.00")))))
                .andExpect(status().isNotFound());
        assertThat(balanceOf(owner.account())).isEqualByComparingTo("1000");
    }
}
