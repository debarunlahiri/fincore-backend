package com.fincore.gateway.limiter;

import java.time.Duration;
import reactor.core.publisher.Mono;

public interface RateLimiter {

    /**
     * @return true if the call is within the limit and was counted, false if it must be rejected
     */
    Mono<Boolean> tryAcquire(String key, int limit, Duration window);
}
