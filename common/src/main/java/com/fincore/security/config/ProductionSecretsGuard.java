package com.fincore.security.config;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start in the {@code prod} profile while any secret still has its development default.
 * The defaults are in the repository, so anyone could forge tokens or call the internal API of a
 * deployment that kept them.
 */
@Component
@Profile("prod")
public class ProductionSecretsGuard implements InitializingBean {

    static final String DEV_JWT_SECRET =
            "ZmluY29yZS1kZXYtb25seS1zZWNyZXQta2V5LW5vdC1mb3ItcHJvZHVjdGlvbi0yMDI2";
    static final String DEV_INTERNAL_KEY = "dev-internal-key-change-me";
    static final String DEV_ADMIN_PASSWORD = "ChangeMe!123";

    private final Environment env;

    public ProductionSecretsGuard(Environment env) {
        this.env = env;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems =
                problems(
                        env.getProperty("fincore.security.jwt.secret", ""),
                        env.getProperty("fincore.internal.api-key", ""),
                        env.getProperty("fincore.bootstrap-admin.password", ""));
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start in the prod profile with weak or default secrets: "
                            + String.join("; ", problems));
        }
    }

    static List<String> problems(String jwtSecret, String internalKey, String adminPassword) {
        List<String> problems = new ArrayList<>();
        if (jwtSecret.isBlank()
                || DEV_JWT_SECRET.equals(jwtSecret)
                || decodedLength(jwtSecret) < 32) {
            problems.add("JWT_SECRET must be set to your own Base64 value of at least 32 bytes");
        }
        if (internalKey.isBlank()
                || DEV_INTERNAL_KEY.equals(internalKey)
                || internalKey.length() < 24) {
            problems.add(
                    "INTERNAL_API_KEY must be set to your own random value of at least 24 characters");
        }
        if (DEV_ADMIN_PASSWORD.equals(adminPassword)) {
            problems.add("ADMIN_PASSWORD must not be the development default");
        }
        return problems;
    }

    private static int decodedLength(String base64) {
        try {
            return Base64.getDecoder().decode(base64).length;
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }
}
