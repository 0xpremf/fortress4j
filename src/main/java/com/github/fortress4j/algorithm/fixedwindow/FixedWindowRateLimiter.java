package com.github.fortress4j.algorithm.fixedwindow;

import com.github.fortress4j.WindowState;
import com.github.fortress4j.config.FixedWindowConfig;

import com.github.fortress4j.models.RateLimiter;


import java.awt.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class FixedWindowRateLimiter implements RateLimiter {

    private final FixedWindowConfig config;
    private final ConcurrentHashMap<String, WindowState> storage = new ConcurrentHashMap<>();
    private final int limit;
    private final Duration windowSize;
    private final Clock clock;

    public FixedWindowRateLimiter(FixedWindowConfig config){
        this(config, Clock.systemUTC());
    }



    public FixedWindowRateLimiter(FixedWindowConfig config, Clock clock) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(storage);

        this.config = config;
        this.limit= config.limit();
        this.windowSize=config.windowSize();
        this.clock = Objects.requireNonNull(clock);


    }


    @Override
    public Boolean tryAcquire(String key) {
        AtomicBoolean allowed = new AtomicBoolean(false);
        storage.compute(key, (k, state) -> {
            Instant now = Instant.now(clock);
            //Check if Window Exist
            if(state==null){
                allowed.set(true);
                return new WindowState(windowSize);
            }

            //Windows Expired
            if(now.isAfter(state.getWindowEnd())) {
                state.resetState(windowSize);
                allowed.set(true);
                return state;
            }

            if(state.getRequestCount()>=limit){
                allowed.set(false);
                return state;
            }
             //Increment in Request
            state.incerementRequestCount();
            allowed.set(true);
            return state;

        });
        return allowed.get();

    }
}
