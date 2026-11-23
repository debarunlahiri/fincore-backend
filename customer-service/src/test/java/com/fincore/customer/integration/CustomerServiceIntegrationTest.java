package com.fincore.customer.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fincore.AbstractIntegrationTest;
import com.fincore.common.exception.FincoreException;
import com.fincore.customer.entity.Customer;
import com.fincore.customer.entity.KycStatus;
import com.fincore.customer.repository.CustomerRepository;
import com.fincore.customer.service.CustomerService;
import com.fincore.customer.service.UserRegisteredHandler;
import com.fincore.security.filter.InternalApiKeyFilter;
import com.fincore.security.model.Role;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

public class CustomerServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRegisteredHandler handler;
    @Autowired CustomerRepository customers;
    @Autowired CustomerService customerService;
    @Value("${fincore.internal.api-key}") String internalKey;

    private UUID provision(String fullName) {
        UUID userId = UUID.randomUUID();
        handler.handle(UUID.randomUUID(), registeredEvent(userId, fullName, "1990-01-01"));
        return userId;
    }

    @Test
    void userRegisteredEventCreatesAPendingProfile() {
        UUID userId = provision("Alice Example");

        Customer c = customers.findByUserId(userId).orElseThrow();
        assertThat(c.getFullName()).isEqualTo("Alice Example");
        assertThat(c.getKycStatus()).isEqualTo(KycStatus.PENDING);
        assertThat(c.getDateOfBirth().toString()).isEqualTo("1990-01-01");
    }

    @Test
    void redeliveredEventIsSkippedAndCreatesNoSecondProfile() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String payload = registeredEvent(userId, "Bob", "1985-05-05");

        assertThat(handler.handle(eventId, payload)).isTrue();
        assertThat(handler.handle(eventId, payload)).isFalse();

        assertThat(customers.findAll().stream().filter(c -> c.getUserId().equals(userId))).hasSize(1);
    }

    @Test
    void sameUserInTwoDifferentEventsStillYieldsOneProfile() {
        UUID userId = UUID.randomUUID();

        handler.handle(UUID.randomUUID(), registeredEvent(userId, "Carol", "1990-01-01"));
        handler.handle(UUID.randomUUID(), registeredEvent(userId, "Carol Again", "1990-01-01"));

        assertThat(customers.findAll().stream().filter(c -> c.getUserId().equals(userId))).hasSize(1);
        assertThat(customers.findByUserId(userId).orElseThrow().getFullName()).isEqualTo("Carol");
    }

    @Test
    void concurrentDeliveriesOfTheSameEventCreateExactlyOneProfile() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String payload = registeredEvent(userId, "Dave", "1990-01-01");
        var pool = Executors.newFixedThreadPool(6);
        try {
            var futures = IntStream.range(0, 6)
                    .mapToObj(i -> pool.submit((Callable<Boolean>) () -> {
                        try {
                            return handler.handle(eventId, payload);
                        } catch (RuntimeException e) {
                            return false;
                        }
                    }))
                    .toList();
            long created = 0;
            for (var f : futures) {
                if (f.get()) {
                    created++;
                }
            }
            assertThat(created).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(customers.findAll().stream().filter(c -> c.getUserId().equals(userId))).hasSize(1);
    }

    @Test
    void malformedOrIncompleteEventsThrowSoTheyCanBeRetriedAndDeadLettered() {
        assertThatThrownBy(() -> handler.handle(UUID.randomUUID(), "{not json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.handle(UUID.randomUUID(), "{\"userId\":\"" + UUID.randomUUID() + "\"}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.handle(UUID.randomUUID(), registeredEvent(UUID.randomUUID(), "X", "not-a-date")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void profileCanBeReadAndUpdatedThroughTheApi() throws Exception {
        UUID userId = provision("Eve");
        String auth = token(userId, Role.CUSTOMER);

        mvc.perform(get("/api/v1/customers/me").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Eve"))
                .andExpect(jsonPath("$.kycStatus").value("PENDING"));
        mvc.perform(put("/api/v1/customers/me")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("fullName", "Eve New", "phone", "+919999999999"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Eve New"));
    }

    @Test
    void profileBeforeTheEventIsProcessedIs404WithAClearCode() throws Exception {
        mvc.perform(get("/api/v1/customers/me").header(HttpHeaders.AUTHORIZATION, token(UUID.randomUUID(), Role.CUSTOMER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));
    }

    @Test
    void invalidProfileUpdateIsRejected() throws Exception {
        UUID userId = provision("Frank");

        mvc.perform(put("/api/v1/customers/me")
                        .header(HttpHeaders.AUTHORIZATION, token(userId, Role.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("fullName", "", "phone", "abc"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.phone").exists());
    }

    @Test
    void kycVerifiesWellFormedDocumentsAndCannotBeRepeatedOnceVerified() throws Exception {
        UUID userId = provision("Grace");
        String auth = token(userId, Role.CUSTOMER);
        String kyc = objectMapper.writeValueAsString(Map.of("documentType", "PAN", "documentNumber", "ABCDE1234F"));

        mvc.perform(post("/api/v1/customers/me/kyc")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(kyc))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycStatus").value("VERIFIED"));
        mvc.perform(post("/api/v1/customers/me/kyc")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(kyc))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KYC_ALREADY_VERIFIED"));
    }

    @Test
    void rejectedKycCanBeRetriedWithAValidDocument() throws Exception {
        UUID userId = provision("Heidi");
        String auth = token(userId, Role.CUSTOMER);

        mvc.perform(post("/api/v1/customers/me/kyc")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("documentType", "PAN", "documentNumber", "000999111"))))
                .andExpect(jsonPath("$.kycStatus").value("REJECTED"));
        mvc.perform(post("/api/v1/customers/me/kyc")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("documentType", "PAN", "documentNumber", "ABCDE1234F"))))
                .andExpect(jsonPath("$.kycStatus").value("VERIFIED"));
    }

    @Test
    void adminCannotUseTheCustomerSelfServiceApi() throws Exception {
        mvc.perform(get("/api/v1/customers/me").header(HttpHeaders.AUTHORIZATION, token(UUID.randomUUID(), Role.ADMIN)))
                .andExpect(status().isForbidden());
    }

    @Test
    void internalLookupRequiresTheInternalKeyAndReturnsKycStatus() throws Exception {
        UUID userId = provision("Ivan");

        mvc.perform(get("/internal/customers/by-user/" + userId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/customers/by-user/" + userId).header(InternalApiKeyFilter.HEADER, "wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/customers/by-user/" + userId).header(HttpHeaders.AUTHORIZATION, token(userId, Role.CUSTOMER)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/customers/by-user/" + userId).header(InternalApiKeyFilter.HEADER, internalKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.kycStatus").value("PENDING"))
                .andExpect(jsonPath("$.customerId").exists());
    }

    @Test
    void internalLookupOfUnknownUserIs404() throws Exception {
        mvc.perform(get("/internal/customers/by-user/" + UUID.randomUUID()).header(InternalApiKeyFilter.HEADER, internalKey))
                .andExpect(status().isNotFound());
    }

    @Test
    void serviceLevelLookupOfUnknownUserIsAFincoreException() {
        assertThatThrownBy(() -> customerService.requireByUserId(UUID.randomUUID()))
                .isInstanceOf(FincoreException.class);
    }
}
