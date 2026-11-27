package com.fincore.gateway.filter;

import com.fincore.gateway.limiter.RateLimiter;
import com.fincore.gateway.security.ClientIpResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Layered rate limiting, on by default. Every request counts against a per-address flood limit (per
 * second and per minute, the DDoS floor); sensitive endpoints add tighter limits on top (see {@link
 * RateLimitProperties}). The gateway does not parse JWTs, so a "session" is a hash of the
 * Authorization header; because that header can be faked, the per-address limits are what actually
 * stop an attacker, the per-session limits only protect honest users from each other and from their
 * own runaway clients. The client address comes from {@link ClientIpResolver}, which only trusts
 * X-Forwarded-For from our own proxies.
 */
@Component
@ConditionalOnProperty(
        name = "fincore.ratelimit.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RateLimitFilter implements GlobalFilter, Ordered {

    static final Duration SECOND = Duration.ofSeconds(1);
    static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Pattern MONEY_POST =
            Pattern.compile(
                    "^/api/v1/(transfers|payments|deposits|loans|cards)(/.*)?$|^/api/v1/accounts/[^/]+/(deposits|withdrawals)$");

    private record Check(String key, int limit, Duration window) {}

    private record Result(Check check, boolean allowed) {}

    private final RateLimiter limiter;
    private final ClientIpResolver clientIps;
    private final RateLimitProperties props;

    public RateLimitFilter(
            RateLimiter limiter, ClientIpResolver clientIps, RateLimitProperties props) {
        this.limiter = limiter;
        this.clientIps = clientIps;
        this.props = props;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String ip = clientIps.resolve(request);
        List<Check> checks = checksFor(request, ip);

        // evaluate in order and stop at the first limit that is exceeded
        return Flux.fromIterable(checks)
                .concatMap(
                        c ->
                                limiter.tryAcquire(c.key(), c.limit(), c.window())
                                        .map(ok -> new Result(c, ok)))
                .filter(r -> !r.allowed())
                .next()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(
                        exceeded ->
                                exceeded.isPresent()
                                        ? reject(exchange, exceeded.get().check())
                                        : chain.filter(exchange));
    }

    private List<Check> checksFor(ServerHttpRequest request, String ip) {
        String path = request.getURI().getPath();
        HttpMethod method = request.getMethod();
        List<Check> checks = new ArrayList<>();
        checks.add(new Check("ip-s:" + ip, props.ipPerSecond(), SECOND));
        checks.add(new Check("ip-m:" + ip, props.ipPerMinute(), MINUTE));

        boolean write =
                !(HttpMethod.GET.equals(method)
                        || HttpMethod.HEAD.equals(method)
                        || HttpMethod.OPTIONS.equals(method));
        if (path.startsWith("/api/v1/auth/")) {
            checks.add(new Check("auth:" + ip, props.authPerMinute(), MINUTE));
        } else if (HttpMethod.POST.equals(method) && path.endsWith("/otp")) {
            checks.add(new Check("otp:" + session(request, ip), props.otpPerMinute(), MINUTE));
        } else if (HttpMethod.POST.equals(method) && MONEY_POST.matcher(path).matches()) {
            checks.add(new Check("money:" + session(request, ip), props.moneyPerMinute(), MINUTE));
        } else if (write) {
            checks.add(new Check("write:" + session(request, ip), props.writePerMinute(), MINUTE));
        }
        return checks;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    private static Mono<Void> reject(ServerWebExchange exchange, Check exceeded) {
        long retryAfter = exceeded.window().toSeconds();
        exchange.getResponse()
                .getHeaders()
                .set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        exchange.getResponse()
                .getHeaders()
                .set("X-RateLimit-Limit", String.valueOf(exceeded.limit()));
        return ProblemResponses.reject(
                exchange,
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMITED",
                "Too many requests. Please wait " + retryAfter + " seconds and try again.");
    }

    private static String session(ServerHttpRequest request, String ip) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            return "anon:" + ip;
        }
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(authorization.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
