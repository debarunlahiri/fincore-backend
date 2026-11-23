package com.fincore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fincore.account.client.CustomerClient;
import com.fincore.account.entity.Account;
import com.fincore.account.entity.AccountType;
import com.fincore.account.repository.AccountRepository;
import com.fincore.account.service.AccountService;
import com.fincore.ledger.service.CashService;
import com.fincore.reporting.service.ReconciliationService;
import com.fincore.security.jwt.JwtService;
import com.fincore.security.model.AuthenticatedUser;
import com.fincore.security.model.Role;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for tests that need the real stack. One PostgreSQL container (singleton, started once per JVM) is shared
 * by every subclass, so Spring also reuses a single application context. The guarantees under test (row locks,
 * unique constraints, triggers, isolation) are database behaviour that H2 would only imitate.
 * The customer-service is the only remote dependency and is replaced by a mock that reports KYC as verified.
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

    @MockitoBean protected CustomerClient customerClient;

    @Autowired protected AccountService accountService;
    @Autowired protected AccountRepository accounts;
    @Autowired protected CashService cash;
    @Autowired protected ReconciliationService reconciliation;
    @Autowired protected JwtService jwtService;
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper objectMapper;

    @BeforeEach
    void kycIsVerifiedByDefault() {
        when(customerClient.byUser(any()))
                .thenAnswer(inv -> new CustomerClient.CustomerSummary(UUID.randomUUID(), inv.getArgument(0), "VERIFIED"));
    }

    protected record TestCustomer(AuthenticatedUser user, Account account) {}

    /** Opens a savings account for a brand-new user id and funds it. */
    protected TestCustomer newCustomer(BigDecimal openingBalance) {
        UUID userId = UUID.randomUUID();
        String name = "user" + userId.toString().substring(0, 8);
        Account account = accountService.open(userId, AccountType.SAVINGS);
        if (openingBalance.signum() > 0) {
            cash.deposit(userId, account.getId(), openingBalance);
        }
        return new TestCustomer(new AuthenticatedUser(userId, name, Role.CUSTOMER), account);
    }

    protected BigDecimal balanceOf(Account account) {
        return accounts.findById(account.getId()).orElseThrow().getBalance();
    }

    protected String bearer(UUID userId, Role role) {
        return "Bearer " + jwtService.generateAccessToken(userId, "user-" + userId.toString().substring(0, 6), role);
    }

    protected String bearer(TestCustomer customer) {
        return bearer(customer.user().userId(), Role.CUSTOMER);
    }

    protected String adminBearer() {
        return bearer(UUID.randomUUID(), Role.ADMIN);
    }

    protected String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    protected static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return task.call();
                }));
            }
            startGate.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
