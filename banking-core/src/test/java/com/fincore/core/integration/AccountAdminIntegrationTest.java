package com.fincore.core.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fincore.AbstractIntegrationTest;
import com.fincore.audit.repository.AuditRepository;
import com.fincore.common.exception.FincoreException;
import com.fincore.core.dto.TransferRequest;
import com.fincore.core.service.CashService;
import com.fincore.core.service.TransferService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;

public class AccountAdminIntegrationTest extends AbstractIntegrationTest {

    @Autowired TransferService transferService;
    @Autowired AuditRepository auditRepository;

    private void transfer(TestCustomer from, TestCustomer to, String amount) {
        transferService.transfer(
                from.user(),
                UUID.randomUUID().toString(),
                new TransferRequest(
                        from.account().getAccountNumber(),
                        to.account().getAccountNumber(),
                        new BigDecimal(amount),
                        "INR",
                        null));
    }

    private static String code(Throwable t) {
        return ((FincoreException) t).code();
    }

    @Test
    void frozenAccountCannotSendReceiveDepositOrWithdraw() {
        TestCustomer a = newCustomer(new BigDecimal("1000"));
        TestCustomer b = newCustomer(new BigDecimal("1000"));
        accountService.setFrozen(a.account().getId(), true, "admin");

        assertThatThrownBy(() -> transfer(a, b, "10"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ACCOUNT_NOT_ACTIVE"));
        assertThatThrownBy(() -> transfer(b, a, "10"))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ACCOUNT_NOT_ACTIVE"));
        assertThatThrownBy(
                        () -> cash.deposit(a.user().userId(), a.account().getId(), BigDecimal.TEN))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ACCOUNT_NOT_ACTIVE"));
        assertThatThrownBy(
                        () -> cash.withdraw(a.user().userId(), a.account().getId(), BigDecimal.TEN))
                .satisfies(e -> assertThat(code(e)).isEqualTo("ACCOUNT_NOT_ACTIVE"));
        assertThat(balanceOf(a.account())).isEqualByComparingTo("1000");
        assertThat(balanceOf(b.account())).isEqualByComparingTo("1000");
    }

    @Test
    void unfrozenAccountWorksAgain() {
        TestCustomer a = newCustomer(new BigDecimal("1000"));
        TestCustomer b = newCustomer(BigDecimal.ZERO);
        accountService.setFrozen(a.account().getId(), true, "admin");
        accountService.setFrozen(a.account().getId(), false, "admin");

        transfer(a, b, "10");

        assertThat(balanceOf(b.account())).isEqualByComparingTo("10");
    }

    @Test
    void freezeAndUnfreezeAreAudited() {
        TestCustomer a = newCustomer(BigDecimal.ZERO);
        String actor = "auditor-" + UUID.randomUUID().toString().substring(0, 6);

        accountService.setFrozen(a.account().getId(), true, actor);
        accountService.setFrozen(a.account().getId(), false, actor);

        var actions =
                auditRepository
                        .findByActorOrderByCreatedAtDesc(actor, PageRequest.of(0, 10))
                        .stream()
                        .map(e -> e.getAction())
                        .toList();
        assertThat(actions).containsExactlyInAnyOrder("ACCOUNT_FROZEN", "ACCOUNT_UNFROZEN");
    }

    @Test
    void theSystemAccountCannotBeFrozen() {
        assertThatThrownBy(
                        () ->
                                accountService.setFrozen(
                                        CashService.SYSTEM_ACCOUNT_ID, true, "admin"))
                .isInstanceOf(FincoreException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("INVALID_ACCOUNT_TYPE"));
    }

    @Test
    void unknownAccountIs404() {
        assertThatThrownBy(() -> accountService.setFrozen(UUID.randomUUID(), true, "admin"))
                .isInstanceOf(FincoreException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("NOT_FOUND"));
    }

    @Test
    void onlyAdminsCanFreezeThroughTheApi() throws Exception {
        TestCustomer a = newCustomer(BigDecimal.ZERO);

        mvc.perform(
                        post("/api/v1/admin/accounts/" + a.account().getAccountNumber() + "/freeze")
                                .header(HttpHeaders.AUTHORIZATION, bearer(a)))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/v1/admin/accounts/" + a.account().getAccountNumber() + "/freeze")
                                .header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
        mvc.perform(
                        post("/api/v1/admin/accounts/"
                                        + a.account().getAccountNumber()
                                        + "/unfreeze")
                                .header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}
