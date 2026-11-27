package com.fincore.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class RequestGuardFilterTest {

    private final GatewayFilterChain chain = mock(GatewayFilterChain.class);
    private final RequestGuardFilter filter = new RequestGuardFilter(1_000);

    RequestGuardFilterTest() {
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    private MockServerWebExchange run(MockServerHttpRequest request) {
        var ex = MockServerWebExchange.from(request);
        filter.filter(ex, chain).block();
        return ex;
    }

    @Test
    void normalJsonRequestsPass() {
        var ex =
                run(
                        MockServerHttpRequest.post("/api/v1/transfers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Content-Length", "50")
                                .build());

        verify(chain).filter(ex);
    }

    @Test
    void unusualMethodsAreRefused() {
        var ex = run(MockServerHttpRequest.method(HttpMethod.TRACE, "/api/v1/accounts").build());

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        verify(chain, never()).filter(any());
    }

    @Test
    void pathTraversalAndEncodedTricksAreRefused() {
        for (String path :
                new String[] {
                    "/api/v1/../actuator", "/api/v1/%2e%2e/x", "/api/v1/a%00b", "/api/v1/a%5cb"
                }) {
            var ex =
                    run(
                            MockServerHttpRequest.method(HttpMethod.GET, java.net.URI.create(path))
                                    .build());
            assertThat(ex.getResponse().getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        verify(chain, never()).filter(any());
    }

    @Test
    void oversizedDeclaredBodiesAreRefusedBeforeAnyWork() {
        var ex =
                run(
                        MockServerHttpRequest.post("/api/v1/transfers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Content-Length", "5000")
                                .build());

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void aBodyThatIsNotJsonIsRefused() {
        var ex =
                run(
                        MockServerHttpRequest.post("/api/v1/transfers")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .header("Content-Length", "20")
                                .build());

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void anEmptyPostWithoutContentTypeIsFine() {
        var ex = run(MockServerHttpRequest.post("/api/v1/loans/LON1/decline").build());

        verify(chain).filter(ex);
    }

    @Test
    void veryLongQueryStringsAreRefused() {
        var ex = run(MockServerHttpRequest.get("/api/v1/transfers?q=" + "a".repeat(5000)).build());

        assertThat(ex.getResponse().getStatusCode()).isEqualTo(HttpStatus.URI_TOO_LONG);
    }
}
