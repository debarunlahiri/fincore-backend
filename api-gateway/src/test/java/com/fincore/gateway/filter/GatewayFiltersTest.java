package com.fincore.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fincore.gateway.limiter.RateLimiter;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public class GatewayFiltersTest {

    private final GatewayFilterChain chain = mock(GatewayFilterChain.class);
    private final RateLimiter limiter = mock(RateLimiter.class);

    GatewayFiltersTest() {
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private static MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> request) {
        return MockServerWebExchange.from(request.build());
    }

    // --- internal path blocking -------------------------------------------------------------------------------

    @Test
    void internalPathsAreAnsweredWith404AndNeverForwarded() {
        var filter = new InternalPathBlockFilter();
        for (String path : new String[] {"/internal/ledger/payment-debits", "/internal/accounts/x", "/internal"}) {
            var ex = exchange(MockServerHttpRequest.post(path));

            filter.filter(ex, chain).block();

            assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        verify(chain, never()).filter(any());
    }

    @Test
    void publicPathsAreForwarded() {
        var ex = exchange(MockServerHttpRequest.get("/api/v1/accounts"));

        new InternalPathBlockFilter().filter(ex, chain).block();

        verify(chain).filter(ex);
    }

    @Test
    void aPathThatMerelyContainsInternalIsNotBlocked() {
        var ex = exchange(MockServerHttpRequest.get("/api/v1/accounts/internal-notes"));

        new InternalPathBlockFilter().filter(ex, chain).block();

        verify(chain).filter(ex);
    }

    // --- request id -------------------------------------------------------------------------------------------

    @Test
    void aMissingRequestIdIsGeneratedForwardedAndEchoed() {
        var ex = exchange(MockServerHttpRequest.get("/api/v1/accounts"));

        new RequestIdFilter().filter(ex, chain).block();

        ArgumentCaptor<ServerWebExchange> forwarded = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(forwarded.capture());
        String id = forwarded.getValue().getRequest().getHeaders().getFirst(RequestIdFilter.HEADER);
        assertThat(id).isNotBlank();
        assertThat(ex.getResponse().getHeaders().getFirst(RequestIdFilter.HEADER)).isEqualTo(id);
    }

    @Test
    void aReasonableIncomingRequestIdIsKeptButAbsurdOnesAreReplaced() {
        var good = exchange(MockServerHttpRequest.get("/x").header(RequestIdFilter.HEADER, "abc-123"));
        new RequestIdFilter().filter(good, chain).block();
        assertThat(good.getResponse().getHeaders().getFirst(RequestIdFilter.HEADER)).isEqualTo("abc-123");

        var huge = exchange(MockServerHttpRequest.get("/x").header(RequestIdFilter.HEADER, "x".repeat(500)));
        new RequestIdFilter().filter(huge, chain).block();
        assertThat(huge.getResponse().getHeaders().getFirst(RequestIdFilter.HEADER)).hasSizeLessThanOrEqualTo(64);
    }

    // --- rate limiting ----------------------------------------------------------------------------------------

    private RateLimitFilter rateLimitFilter() {
        return new RateLimitFilter(limiter, 10, 20);
    }

    @Test
    void requestWithinTheLimitContinuesDownTheChain() {
        when(limiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(Mono.just(true));
        var ex = exchange(MockServerHttpRequest.post("/api/v1/auth/login"));

        rateLimitFilter().filter(ex, chain).block();

        verify(chain).filter(ex);
    }

    @Test
    void requestOverTheLimitGets429WithRetryAfterAndIsNotForwarded() {
        when(limiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(Mono.just(false));
        var ex = exchange(MockServerHttpRequest.post("/api/v1/auth/login"));

        rateLimitFilter().filter(ex, chain).block();

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ex.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(ex.getResponse().getBodyAsString().block()).contains("RATE_LIMITED");
        verify(chain, never()).filter(any());
    }

    @Test
    void authEndpointsAreLimitedPerClientAddressUsingForwardedFor() {
        when(limiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(Mono.just(true));
        var ex = exchange(MockServerHttpRequest.post("/api/v1/auth/login").header("X-Forwarded-For", "203.0.113.9, 10.0.0.1"));

        rateLimitFilter().filter(ex, chain).block();

        verify(limiter).tryAcquire(eq("auth:203.0.113.9"), eq(10), eq(Duration.ofMinutes(1)));
    }

    @Test
    void moneyMovingPostsAreLimitedPerSessionAndDifferentSessionsHaveDifferentKeys() {
        when(limiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(Mono.just(true));

        rateLimitFilter().filter(exchange(MockServerHttpRequest.post("/api/v1/transfers").header("Authorization", "Bearer a")), chain).block();
        rateLimitFilter().filter(exchange(MockServerHttpRequest.post("/api/v1/transfers").header("Authorization", "Bearer b")), chain).block();
        rateLimitFilter().filter(exchange(MockServerHttpRequest.post("/api/v1/payments").header("Authorization", "Bearer a")), chain).block();

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(limiter, org.mockito.Mockito.times(3)).tryAcquire(keys.capture(), eq(20), any());
        assertThat(keys.getAllValues().get(0)).startsWith("money:").isNotEqualTo(keys.getAllValues().get(1));
        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(2));
        assertThat(keys.getAllValues().get(0)).doesNotContain("Bearer");
    }

    @Test
    void readsAndOtherEndpointsAreNotRateLimited() {
        rateLimitFilter().filter(exchange(MockServerHttpRequest.get("/api/v1/transfers/123")), chain).block();
        rateLimitFilter().filter(exchange(MockServerHttpRequest.get("/api/v1/accounts")), chain).block();
        rateLimitFilter().filter(exchange(MockServerHttpRequest.post("/api/v1/accounts")), chain).block();

        verify(limiter, never()).tryAcquire(anyString(), anyInt(), any());
        verify(chain, org.mockito.Mockito.times(3)).filter(any());
    }
}
