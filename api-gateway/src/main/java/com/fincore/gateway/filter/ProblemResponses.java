package com.fincore.gateway.filter;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Writes the same RFC 7807 style error body the services use, for requests the gateway itself
 * rejects.
 */
final class ProblemResponses {

    private ProblemResponses() {}

    static Mono<Void> reject(
            ServerWebExchange exchange, HttpStatus status, String code, String detail) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        String body =
                "{\"status\":"
                        + status.value()
                        + ",\"title\":\""
                        + status.getReasonPhrase()
                        + "\",\"detail\":\""
                        + detail.replace("\"", "'")
                        + "\",\"code\":\""
                        + code
                        + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
