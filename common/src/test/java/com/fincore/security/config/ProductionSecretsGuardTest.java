package com.fincore.security.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class ProductionSecretsGuardTest {

    private static final String GOOD_JWT =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef-extra".getBytes());

    @Test
    void developmentDefaultsAreRejected() {
        var problems =
                ProductionSecretsGuard.problems(
                        ProductionSecretsGuard.DEV_JWT_SECRET,
                        ProductionSecretsGuard.DEV_INTERNAL_KEY,
                        ProductionSecretsGuard.DEV_ADMIN_PASSWORD);

        assertThat(problems).hasSize(3);
    }

    @Test
    void missingOrShortSecretsAreRejected() {
        assertThat(ProductionSecretsGuard.problems("", "", "")).hasSize(2);
        assertThat(ProductionSecretsGuard.problems("c2hvcnQ=", "short", "x")).hasSize(2);
        assertThat(ProductionSecretsGuard.problems("not base64 !!", "x".repeat(30), "x"))
                .hasSize(1);
    }

    @Test
    void realSecretsPass() {
        assertThat(
                        ProductionSecretsGuard.problems(
                                GOOD_JWT, "a-long-random-internal-key-12345", "Str0ng!Admin#Pass"))
                .isEmpty();
    }
}
