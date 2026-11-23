package com.fincore.gateway.filter;

import com.fincore.gateway.limiter.RateLimiter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Per-client limit on authentication endpoints (brute-force protection) and per-session limit on money-moving
 * POSTs. The gateway does not parse JWTs, so "session" is a hash of the Authorization header.
 * Behind an ingress the client address comes from X-Forwarded-For (the ingress must overwrite it).
 */
@Component
@ConditionalOnProperty(name = "fincore.ratelimit.enabled", havingValue = "true")
public class RateLimitFilter implements GlobalFilter, Ordered {

    static final Duration WINDOW = Duration.ofMinutes(1);

    private final RateLimiter limiter;
    private final int authPerMinute;
    private final int moneyPerMinute;

    public RateLimitFilter(
            RateLimiter limiter,
            @Value("${fincore.ratelimit.auth-per-minute:10}") int authPerMinute,
            @Value("${fincore.ratelimit.money-per-minute:20}") int moneyPerMinute) {
        this.limiter = limiter;
        this.authPerMinute = authPerMinute;
        this.moneyPerMinute = moneyPerMinute;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        Mono<Boolean> allowed;
        if (path.startsWith("/api/v1/auth/")) {
            allowed = limiter.tryAcquire("auth:" + clientAddress(request), authPerMinute, WINDOW);
        } else if (HttpMethod.POST.equals(request.getMethod())
                && (path.equals("/api/v1/transfers") || path.equals("/api/v1/payments"))) {
            allowed = limiter.tryAcquire("money:" + sessionKey(request), moneyPerMinute, WINDOW);
        } else {
            return chain.filter(exchange);
        }
        return allowed.flatMap(ok -> ok ? chain.filter(exchange) : reject(exchange));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    private static Mono<Void> reject(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(WINDOW.toSeconds()));
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        byte[] body = ("{\"status\":429,\"title\":\"Too Many Requests\","
                        + "\"detail\":\"Rate limit exceeded, retry later\",\"code\":\"RATE_LIMITED\"}")
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    private static String clientAddress(ServerHttpRequest request) {
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    private static String sessionKey(ServerHttpRequest request) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            return "anon:" + clientAddress(request);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(authorization.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
