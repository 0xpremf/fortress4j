package com.github.fortress4j.algorithm.fixedwindow;

import com.github.fortress4j.WindowState;
import com.github.fortress4j.config.FixedWindowConfig;

import com.github.fortress4j.models.RateLimiter;
import com.github.fortress4j.storage.InMemoryStorage;


import java.awt.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class FixedWindowRateLimiter implements RateLimiter {

    private final FixedWindowConfig config;
    private final InMemoryStorage storage;
    private final int limit;
    private final Duration windowSize;
    private final Clock clock;

    public FixedWindowRateLimiter(FixedWindowConfig config){
        this(config, Clock.systemUTC());
    }

    public FixedWindowRateLimiter(InMemoryStorage storage){
        this.storage = storage;
    }

    public FixedWindowRateLimiter(FixedWindowConfig config, Clock clock,InMemoryStorage  storage) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(storage);

        this.config = config;
        this.limit= config.limit();
        this.windowSize=config.windowSize();
        this.clock = Objects.requireNonNull(clock);


    }


    @Override
    public Boolean tryAcquire(String key) {


    }
}
