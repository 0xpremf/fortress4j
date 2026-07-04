package com.github.fortress4j.config;

import java.time.Duration;
import java.util.Objects;

public record FixedWindowConfig(int limit, Duration window) implements RateLimiterConfig {
    public FixedWindowConfig {
        Objects.requireNonNull(window, "window must not be null");

        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }

        if (window.compareTo(Duration.ZERO) <= 0) {
            throw new IllegalArgumentException("window must be greater than zero");
        }
    }
}