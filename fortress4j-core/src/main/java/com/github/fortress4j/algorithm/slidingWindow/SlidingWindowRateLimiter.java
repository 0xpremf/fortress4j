package com.github.fortress4j.algorithm.slidingWindow;

import com.github.fortress4j.config.SlidingWindowConfig;
import com.github.fortress4j.models.RateLimitResult;
import com.github.fortress4j.models.RateLimiter;
import com.github.fortress4j.states.SlidingWindowState;
import com.github.fortress4j.storage.InMemoryStorage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class SlidingWindowRateLimiter<K> implements RateLimiter<K> {

    private final SlidingWindowConfig config;
    private final InMemoryStorage<K, SlidingWindowState> storage;
    private final Clock clock;

    public SlidingWindowRateLimiter(SlidingWindowConfig config, InMemoryStorage<K, SlidingWindowState> storage) {
        this(config, storage, Clock.systemUTC());
    }

    public SlidingWindowRateLimiter(SlidingWindowConfig config, InMemoryStorage<K, SlidingWindowState> storage, Clock clock) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    @Override
    public RateLimitResult tryAcquire(K key) {
        Objects.requireNonNull(key, "key cannot be null");

        AtomicBoolean accepted = new AtomicBoolean(false);
        AtomicReference<Double> estimatedRequests = new AtomicReference<>(0.0);
        AtomicReference<Instant> resetAt = new AtomicReference<>();
        AtomicLong retryAfterMs = new AtomicLong(0L);

        storage.compute(key, (userKey, state) -> {
            Instant now = clock.instant();
            long windowMillis = config.windowSize().toMillis();
            Duration winSize = config.windowSize();

            if (state == null) {
                state = new SlidingWindowState(now);
            }

            // How many windows have passed?
            Duration elapsed = Duration.between(state.getCurrentWindowStartTime(), now);
            long elapsedMillis = Math.max(0, elapsed.toMillis());
            long windowsPassed = elapsedMillis / windowMillis;

            if (windowsPassed == 1) {
                state.setPrevWindowCount(state.getCurrentWindowCount());
                state.setCurrentWindowCount(0);
                state.setCurrentWindowStartTime(state.getCurrentWindowStartTime().plus(winSize));
            } else if (windowsPassed > 1) {
                state.setPrevWindowCount(0);
                state.setCurrentWindowCount(0);
                long skippedWindows = windowsPassed;
                state.setCurrentWindowStartTime(state.getCurrentWindowStartTime().plus(winSize.multipliedBy(skippedWindows)));
            }

            long elapsedInsideCurrentWindow = Math.max(0, Duration.between(state.getCurrentWindowStartTime(), now).toMillis());
            double weight = (double) Math.max(0, windowMillis - elapsedInsideCurrentWindow) / windowMillis;
            double estimate = weight * state.getPrevWindowCount() + state.getCurrentWindowCount();

            Instant reset = state.getCurrentWindowStartTime().plus(winSize);
            resetAt.set(reset);

            if (estimate >= config.limit()) {
                accepted.set(false);
                estimatedRequests.set(estimate);
                retryAfterMs.set(Math.max(0, Duration.between(now, reset).toMillis()));
                return state;
            }

            state.setCurrentWindowCount(state.getCurrentWindowCount() + 1);
            estimatedRequests.set(estimate + 1);
            accepted.set(true);
            retryAfterMs.set(0L);

            return state;
        });

        int remainingRequests = Math.max(
                0,
                config.limit() - (int) Math.ceil(estimatedRequests.get())
        );

        return new RateLimitResult(
                accepted.get(),
                remainingRequests,
                retryAfterMs.get(),
                resetAt.get()
        );
    }
}
