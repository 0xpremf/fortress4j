package com.github.fortress4j.config;

import java.time.Duration;
import java.util.Objects;

public record FixedWindowConfig(int limit, Duration windowSize) implements RateLimiterConfig {
    @Override
    public int limit() {
        return limit;
    }

    @Override
    public Duration windowSize() {
        return windowSize;
    }

    public FixedWindowConfig {
        Objects.requireNonNull(windowSize, "window must not be null");

        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }

        if (windowSize.compareTo(Duration.ZERO) <= 0) {
            throw new IllegalArgumentException("window must be greater than zero");
        }

    }
}