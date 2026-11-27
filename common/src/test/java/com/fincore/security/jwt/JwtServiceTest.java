package com.fincore.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import com.fincore.security.model.Role;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class JwtServiceTest {

    private static String secret(String seed) {
        return Base64.getEncoder()
                .encodeToString((seed + "-padding-padding-padding-padding").getBytes());
    }

    private static JwtService service(String secret, Duration ttl) {
        return new JwtService(secret, ttl);
    }

    @Test
    void roundTripPreservesIdentityAndRole() {
        JwtService jwt = service(secret("one"), Duration.ofMinutes(5));
        UUID id = UUID.randomUUID();

        var parsed = jwt.parse(jwt.generateAccessToken(id, "alice", Role.ADMIN));

        assertThat(parsed).isPresent();
        assertThat(parsed.get().userId()).isEqualTo(id);
        assertThat(parsed.get().username()).isEqualTo("alice");
        assertThat(parsed.get().role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void tokenIssuedByOneServiceIsAcceptedByAnotherSharingTheSecret() {
        JwtService authService = service(secret("shared"), Duration.ofMinutes(5));
        JwtService coreService = service(secret("shared"), Duration.ofMinutes(5));

        String token = authService.generateAccessToken(UUID.randomUUID(), "bob", Role.CUSTOMER);

        assertThat(coreService.parse(token)).isPresent();
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService jwt = service(secret("one"), Duration.ofSeconds(-30));

        assertThat(jwt.parse(jwt.generateAccessToken(UUID.randomUUID(), "a", Role.CUSTOMER)))
                .isEmpty();
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String token =
                service(secret("attacker"), Duration.ofMinutes(5))
                        .generateAccessToken(UUID.randomUUID(), "a", Role.ADMIN);

        assertThat(service(secret("server"), Duration.ofMinutes(5)).parse(token)).isEmpty();
    }

    @Test
    void tamperedPayloadIsRejected() {
        JwtService jwt = service(secret("one"), Duration.ofMinutes(5));
        String[] parts =
                jwt.generateAccessToken(UUID.randomUUID(), "a", Role.CUSTOMER).split("\\.");
        String forgedPayload =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString("{\"sub\":\"x\",\"role\":\"ADMIN\"}".getBytes());

        assertThat(jwt.parse(parts[0] + "." + forgedPayload + "." + parts[2])).isEmpty();
    }

    @Test
    void garbageIsRejectedWithoutThrowing() {
        JwtService jwt = service(secret("one"), Duration.ofMinutes(5));

        assertThat(jwt.parse("not-a-jwt")).isEmpty();
        assertThat(jwt.parse("")).isEmpty();
    }
}
