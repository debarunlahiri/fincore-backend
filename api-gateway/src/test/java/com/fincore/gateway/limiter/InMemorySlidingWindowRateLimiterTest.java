package com.fincore.gateway.limiter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class InMemorySlidingWindowRateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final InMemorySlidingWindowRateLimiter limiter =
            new InMemorySlidingWindowRateLimiter(now::get);

    private boolean call(String key, int limit) {
        return Boolean.TRUE.equals(limiter.tryAcquire(key, limit, Duration.ofMinutes(1)).block());
    }

    @Test
    void allowsUpToTheLimitThenRejects() {
        for (int i = 0; i < 3; i++) {
            assertThat(call("k", 3)).isTrue();
        }
        assertThat(call("k", 3)).isFalse();
    }

    @Test
    void theWindowSlidesSoCapacityComesBack() {
        for (int i = 0; i < 3; i++) {
            call("k", 3);
        }
        now.addAndGet(61_000);

        assertThat(call("k", 3)).isTrue();
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 3; i++) {
            call("a", 3);
        }

        assertThat(call("a", 3)).isFalse();
        assertThat(call("b", 3)).isTrue();
    }

    @Test
    void aRejectedCallDoesNotExtendTheBlock() {
        for (int i = 0; i < 3; i++) {
            call("k", 3);
        }
        for (int i = 0; i < 50; i++) {
            call("k", 3); // hammering while blocked must not keep the window full forever
        }
        now.addAndGet(61_000);

        assertThat(call("k", 3)).isTrue();
    }
}
