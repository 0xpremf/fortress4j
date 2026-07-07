package com.github.fortress4j.algorithm.fixedwindow;

import com.github.fortress4j.WindowState;
import com.github.fortress4j.config.FixedWindowConfig;

import com.github.fortress4j.models.RateLimiter;
import com.github.fortress4j.storage.InMemoryStorage;
import com.github.fortress4j.storage.Storage;


import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

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
    public Boolean tryAcquire(K key) {
        AtomicBoolean accepted = new AtomicBoolean(false);
        storage.compute(key,(userKey,state)->{
                Instant now = clock.instant();
                 if(state==null){
                     accepted.set(true);
                     return new WindowState(config.windowSize());
                 }
                if(now.isAfter(state.getWindowEnd())){
                    state.resetState(config.windowSize());
                    accepted.set(true);
                    return state;

                }
                if(state.getRequestCount()>=config.limit()){
                    return state;
                }
                state.incerementRequestCount();
                accepted.set(true);
                return state;
        });
        return accepted.get();

    }
}



