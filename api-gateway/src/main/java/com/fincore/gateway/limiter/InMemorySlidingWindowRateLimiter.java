package com.fincore.gateway.limiter;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Sliding-window log kept in this gateway's memory. It makes rate limiting work with no Redis at
 * all, so a local run is protected too. With several gateway instances each one counts on its own,
 * so use the Redis limiter ({@code RATELIMIT_STORE=redis}) in production for a limit shared across
 * instances.
 *
 * <p>The map is capped: an attacker who rotates addresses or tokens cannot make us run out of
 * memory.
 */
@Component
@ConditionalOnProperty(
        name = "fincore.ratelimit.store",
        havingValue = "memory",
        matchIfMissing = true)
public class InMemorySlidingWindowRateLimiter implements RateLimiter {

    static final int MAX_KEYS = 200_000;
    private static final Logger log =
            LoggerFactory.getLogger(InMemorySlidingWindowRateLimiter.class);

    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final LongSupplier clockMillis;
    private volatile long lastPurge;

    public InMemorySlidingWindowRateLimiter() {
        this(System::currentTimeMillis);
    }

    public InMemorySlidingWindowRateLimiter(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    @Override
    public Mono<Boolean> tryAcquire(String key, int limit, Duration window) {
        return Mono.fromSupplier(() -> allow(key, limit, window.toMillis()));
    }

    boolean allow(String key, int limit, long windowMillis) {
        long now = clockMillis.getAsLong();
        purgeIfDue(now, windowMillis);
        if (hits.size() >= MAX_KEYS && !hits.containsKey(key)) {
            log.warn("Rate limiter key table is full ({} keys), not tracking new key", MAX_KEYS);
            return true;
        }
        Deque<Long> queue = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && queue.peekFirst() <= now - windowMillis) {
                queue.pollFirst();
            }
            if (queue.size() >= limit) {
                return false;
            }
            queue.addLast(now);
            return true;
        }
    }

    /**
     * Drops keys whose newest hit is older than the longest window we use (one minute), at most
     * every 30 seconds.
     */
    private void purgeIfDue(long now, long windowMillis) {
        if (now - lastPurge < 30_000) {
            return;
        }
        lastPurge = now;
        long cutoff = now - Math.max(windowMillis, 60_000);
        hits.entrySet()
                .removeIf(
                        e -> {
                            synchronized (e.getValue()) {
                                Long newest = e.getValue().peekLast();
                                return newest == null || newest <= cutoff;
                            }
                        });
    }
}
