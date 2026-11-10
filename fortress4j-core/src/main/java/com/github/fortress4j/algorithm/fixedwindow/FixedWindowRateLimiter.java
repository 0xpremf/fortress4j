package com.github.fortress4j.algorithm.fixedwindow;

import com.github.fortress4j.models.RateLimitResult;
import com.github.fortress4j.states.FixedWindowState;
import com.github.fortress4j.config.FixedWindowConfig;
import com.github.fortress4j.models.RateLimiter;
import com.github.fortress4j.storage.InMemoryStorage;
import com.github.fortress4j.storage.Storage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class FixedWindowRateLimiter<K> implements RateLimiter<K> {

    private final FixedWindowConfig config;
    private final Storage<K, FixedWindowState> storage;
    private final Clock clock;

    public FixedWindowRateLimiter(FixedWindowConfig config, InMemoryStorage<K, FixedWindowState> storage, Clock clock) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    public FixedWindowRateLimiter(FixedWindowConfig config, InMemoryStorage<K, FixedWindowState> storage) {
        this(config, storage, Clock.systemUTC());
    }

    @Override
    public RateLimitResult tryAcquire(K key) {
        Objects.requireNonNull(key, "key cannot be null");

        AtomicBoolean accepted = new AtomicBoolean(false);
        AtomicLong remainingRequests = new AtomicLong(0);
        AtomicReference<Instant> resetAt = new AtomicReference<>();
        AtomicLong retryAfter = new AtomicLong(0);

        storage.compute(key, (userKey, state) -> {
            Instant now = clock.instant();
            Duration windowSize = config.windowSize();

            if (state == null) {
                state = new FixedWindowState(windowSize, now);
            } else if (!now.isBefore(state.getWindowEnd())) {
                state.resetState(windowSize, now);
            }

            Instant reset = state.getWindowEnd();
            resetAt.set(reset);

            if (state.getRequestCount() >= config.limit()) {
                accepted.set(false);
                remainingRequests.set(0);
                long waitMillis = Math.max(0, Duration.between(now, reset).toMillis());
                retryAfter.set(waitMillis);
                return state;
            }

            state.incrementRequestCount();
            accepted.set(true);
            remainingRequests.set(config.limit() - state.getRequestCount());
            retryAfter.set(0);
            return state;
        });

        return new RateLimitResult(
                accepted.get(),
                remainingRequests.get(),
                retryAfter.get(),
                resetAt.get()
        );
    }
}
