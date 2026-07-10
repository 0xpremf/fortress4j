package com.github.fortress4j.config;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record SlidingWindowConfig(int limit, Duration windowSize) implements RateLimiterConfig {

    public SlidingWindowConfig {
        Objects.requireNonNull(windowSize, "window must not be null");

        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }

        if (windowSize.compareTo(Duration.ZERO) <= 0) {
            throw new IllegalArgumentException("window must be greater than zero");
        }

    }
}
