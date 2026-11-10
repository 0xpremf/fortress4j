package com.github.fortress4j.config;

import java.time.Duration;
import java.util.Objects;

public record FixedWindowConfig(int limit, Duration windowSize) implements RateLimiterConfig {

    public FixedWindowConfig {
        Objects.requireNonNull(windowSize, "window must not be null");

        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }

        if (windowSize.isNegative() || windowSize.isZero()) {
            throw new IllegalArgumentException("window must be greater than zero");
        }
    }

    public FixedWindowConfig(int limit, long windowSizeMillis) {
        this(limit, Duration.ofMillis(windowSizeMillis));
    }
}
