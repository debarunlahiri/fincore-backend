package com.fincore.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

class ClientIpResolverTest {

    private final ClientIpResolver resolver =
            new ClientIpResolver(List.of("127.0.0.1/32", "10.0.0.0/8", "::1/128"));

    private static MockServerHttpRequest request(String peer, String forwarded) {
        var builder =
                MockServerHttpRequest.get("/x").remoteAddress(new InetSocketAddress(peer, 1234));
        if (forwarded != null) {
            builder.header("X-Forwarded-For", forwarded);
        }
        return builder.build();
    }

    @Test
    void aDirectClientIsIdentifiedByItsSocketAndItsForwardedHeaderIsIgnored() {
        assertThat(resolver.resolve(request("198.51.100.7", "1.2.3.4"))).isEqualTo("198.51.100.7");
    }

    @Test
    void behindATrustedProxyTheForwardedClientIsUsed() {
        assertThat(resolver.resolve(request("10.1.2.3", "203.0.113.9"))).isEqualTo("203.0.113.9");
    }

    @Test
    void anAttackerPrependingFakeAddressesCannotChangeTheResult() {
        // the proxy appended the real client (203.0.113.9); everything to the left was sent by the
        // client itself
        assertThat(resolver.resolve(request("10.1.2.3", "9.9.9.9, 8.8.8.8, 203.0.113.9")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void chainedTrustedProxiesAreSkipped() {
        assertThat(resolver.resolve(request("10.1.2.3", "203.0.113.9, 10.9.9.9")))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void garbageInTheHeaderFallsBackToThePeerAndNeverTriggersADnsLookup() {
        assertThat(resolver.resolve(request("10.1.2.3", "evil.example.com"))).isEqualTo("10.1.2.3");
        assertThat(resolver.resolve(request("10.1.2.3", "1.2.3.4, not-an-ip")))
                .isEqualTo("10.1.2.3");
    }

    @Test
    void anAbsentRemoteAddressIsUnknown() {
        assertThat(resolver.resolve(MockServerHttpRequest.get("/x").build())).isEqualTo("unknown");
    }

    @Test
    void invalidTrustedProxyConfigurationFailsFast() {
        assertThatThrownBy(() -> new ClientIpResolver(List.of("not-an-ip/8")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientIpResolver(List.of("10.0.0.0/40")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
