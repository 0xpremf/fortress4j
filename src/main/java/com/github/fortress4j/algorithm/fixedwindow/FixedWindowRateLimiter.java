package com.github.fortress4j.algorithm.fixedwindow;

import com.github.fortress4j.WindowState;
import com.github.fortress4j.config.FixedWindowConfig;

import com.github.fortress4j.models.RateLimitResult;
import com.github.fortress4j.models.RateLimiter;


import java.awt.*;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class FixedWindowRateLimiter implements RateLimiter {

    private final FixedWindowConfig config;
    private ConcurrentHashMap<String, WindowState> storage = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(FixedWindowConfig config) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(storage);
        this.config = config;

    }


    @Override
    public RateLimitResult tryAcquire(String key) {
        storage.compute(key,(String,WindowState) -> (new WindowState(1, Duration.ofMillis(100))));
    }
}
