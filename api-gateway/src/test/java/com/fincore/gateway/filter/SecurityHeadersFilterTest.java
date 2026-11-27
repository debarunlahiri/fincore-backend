package com.fincore.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class SecurityHeadersFilterTest {

    @Test
    void everyResponseGetsTheHardeningHeaders() {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/accounts").build());
        when(chain.filter(any())).thenAnswer(inv -> ex.getResponse().setComplete());

        new SecurityHeadersFilter().filter(ex, chain).block();

        var h = ex.getResponse().getHeaders();
        assertThat(h.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(h.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(h.getFirst("Content-Security-Policy")).contains("default-src 'none'");
        assertThat(h.getFirst("Strict-Transport-Security")).contains("max-age=");
        assertThat(h.getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void errorsTheGatewayWritesItselfAreCoveredToo() {
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/x").build());
        when(chain.filter(any()))
                .thenAnswer(
                        inv ->
                                ProblemResponses.reject(
                                        ex,
                                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                                        "RATE_LIMITED",
                                        "slow down"));

        new SecurityHeadersFilter().filter(ex, chain).block();

        assertThat(ex.getResponse().getHeaders().getFirst("X-Content-Type-Options"))
                .isEqualTo("nosniff");
        assertThat(ex.getResponse().getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
    }
}
