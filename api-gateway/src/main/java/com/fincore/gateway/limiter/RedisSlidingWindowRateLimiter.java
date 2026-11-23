package com.fincore.gateway.limiter;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Sliding-window log in a Redis sorted set (score = request time). The whole check-and-add runs as one Lua
 * script, so it is atomic across gateway instances: no race between counting and recording a request.
 * Fails open: if Redis is unreachable the request is allowed rather than taking the whole API down with the cache.
 */
@Component
@ConditionalOnProperty(name = "fincore.ratelimit.enabled", havingValue = "true")
public class RedisSlidingWindowRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisSlidingWindowRateLimiter.class);

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(
            """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            if redis.call('ZCARD', key) >= limit then
                return 0
            end
            redis.call('ZADD', key, now, ARGV[4])
            redis.call('PEXPIRE', key, window)
            return 1
            """,
            Long.class);

    private final ReactiveStringRedisTemplate redis;

    public RedisSlidingWindowRateLimiter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Boolean> tryAcquire(String key, int limit, Duration window) {
        List<String> args = List.of(
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(window.toMillis()),
                String.valueOf(limit),
                UUID.randomUUID().toString());
        return redis.execute(SCRIPT, List.of("ratelimit:" + key), args)
                .next()
                .map(allowed -> allowed == 1L)
                .defaultIfEmpty(true)
                .onErrorResume(e -> {
                    log.warn("Rate limiter unavailable, allowing request: {}", e.toString());
                    return Mono.just(true);
                });
    }
}
