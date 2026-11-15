package com.github.fortress4j.spring.autoconfigure;

import com.github.fortress4j.algorithm.fixedwindow.FixedWindowRateLimiter;
import com.github.fortress4j.algorithm.slidingWindow.SlidingWindowRateLimiter;
import com.github.fortress4j.algorithm.tokenBucket.TokenBucketRateLimiter;
import com.github.fortress4j.config.FixedWindowConfig;
import com.github.fortress4j.config.SlidingWindowConfig;
import com.github.fortress4j.config.TokenBucketConfig;
import com.github.fortress4j.spring.RateLimiterRegistry.Entry;
import com.github.fortress4j.storage.InMemoryStorage;

public class InMemoryLimiterFactory implements LimiterFactory {

    @Override
    public Entry create(String name, Fortress4jProperties.Limiter spec) {
        return switch (spec.algorithm()) {
            case TOKEN_BUCKET -> {
                var config = tokenBucketConfig(name, spec);
                yield new Entry(new TokenBucketRateLimiter<>(config, new InMemoryStorage<>()), spec.capacity());
            }
            case FIXED_WINDOW -> {
                var config = new FixedWindowConfig(
                        required(spec.limit(), name, "limit"), required(spec.window(), name, "window"));
                yield new Entry(new FixedWindowRateLimiter<>(config, new InMemoryStorage<>()), spec.limit());
            }
            case SLIDING_WINDOW -> {
                var config = new SlidingWindowConfig(
                        required(spec.limit(), name, "limit"), required(spec.window(), name, "window"));
                yield new Entry(new SlidingWindowRateLimiter<>(config, new InMemoryStorage<>()), spec.limit());
            }
        };
    }

    static TokenBucketConfig tokenBucketConfig(String name, Fortress4jProperties.Limiter spec) {
        return new TokenBucketConfig(
                required(spec.capacity(), name, "capacity"),
                required(spec.refillAmount(), name, "refill-amount"),
                required(spec.refillInterval(), name, "refill-interval").toNanos());
    }

    static <T> T required(T value, String limiter, String property) {
        if (value == null) {
            throw new IllegalStateException(
                    "fortress4j.limiters." + limiter + "." + property + " is required for this algorithm");
        }
        return value;
    }
}