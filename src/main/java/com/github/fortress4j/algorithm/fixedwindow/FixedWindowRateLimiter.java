package com.github.fortress4j.algorithm.fixedwindow;

import com.github.fortress4j.models.RateLimitResult;
import com.github.fortress4j.states.WindowState;
import com.github.fortress4j.config.FixedWindowConfig;

import com.github.fortress4j.models.RateLimiter;
import com.github.fortress4j.storage.InMemoryStorage;
import com.github.fortress4j.storage.Storage;


import java.sql.SQLOutput;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class FixedWindowRateLimiter<K> implements RateLimiter<K> {

    private final FixedWindowConfig config;
    private final Storage<K,WindowState> storage;
    private final Clock clock;

    public FixedWindowRateLimiter(FixedWindowConfig config, InMemoryStorage<K, WindowState> storage, Clock clock) {
        this.config = Objects.requireNonNull(config);
        this.storage = Objects.requireNonNull(storage);
        this.clock = Objects.requireNonNull(clock);
    }

    public FixedWindowRateLimiter(FixedWindowConfig config, InMemoryStorage<K, WindowState> storage) {
        this(config, storage,Clock.systemUTC());
    }

    @Override
    public RateLimitResult tryAcquire(K key) {
        AtomicBoolean accepted = new AtomicBoolean(false);
        AtomicReference<Integer> estimatedRequests = new AtomicReference<>(0);
        AtomicReference<Instant> resetAt = new AtomicReference<>();
        AtomicReference<Duration> retryAfter = new AtomicReference<>(Duration.ZERO);
        storage.compute(key,(userKey,state)->{
                Instant now = clock.instant();
                 if(state==null){
                     state = new WindowState(config.windowSize(), now);
                    state.incrementRequestCount();
                     accepted.set(true);
                     return state;
                 }
                if(now.isAfter(state.getWindowEnd())){
                    state.resetState(config.windowSize());
                    state.incrementRequestCount();
                    accepted.set(true);
                    return state;

                }


                if(state.getRequestCount()>=config.limit()){
                    accepted.set(false);
                    Instant reset = state.getWindowEnd();
                    resetAt.set(reset);
                    retryAfter.set(Duration.between(now,reset));



                    return state;

                }
                state.incrementRequestCount();

                accepted.set(true);
                estimatedRequests.set(config.limit() - state.getRequestCount());

                return state;
        });

        return new  RateLimitResult(
                accepted.get(),
                estimatedRequests.get(),
                retryAfter.get(),
                resetAt.get()

                );

    }
}



