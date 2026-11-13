package com.github.fortress4j.algorithm.slindingWindow;

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
import java.util.concurrent.atomic.AtomicReference;

public class SlidingWindowRateLimiter<K> implements RateLimiter<K> {


    SlidingWindowConfig config;
    InMemoryStorage<K, SlidingWindowState>  storage;
    Clock clock;




    public SlidingWindowRateLimiter(SlidingWindowConfig config,InMemoryStorage<K,SlidingWindowState> storage) {
        this(config, storage, Clock.systemUTC());
    }

    public SlidingWindowRateLimiter(SlidingWindowConfig config, InMemoryStorage<K, SlidingWindowState> storage, Clock clock) {
        this.config = Objects.requireNonNull(config);
        this.storage = Objects.requireNonNull(storage);
        this.clock = Objects.requireNonNull(clock);


    }

    @Override
    public RateLimitResult tryAcquire(K key) {

        AtomicBoolean accepted = new AtomicBoolean(false);
        // Now stores the estimate AFTER this operation, on BOTH branches,
        // so the post-processing formula has consistent input.
        AtomicReference<Double> finalEstimate = new AtomicReference<>(0.0);
        AtomicReference<Instant> resetAt = new AtomicReference<>();
        AtomicReference<Duration> retryAfter = new AtomicReference<>(Duration.ZERO);

        storage.compute(key, (userKey, state) -> {

            Instant now = clock.instant();
            long windowMillis = config.windowSize().toMillis();
            var winSize = config.windowSize();

            if (state == null) {
                state = new SlidingWindowState(now);
            }

            Duration elapsed = Duration.between(state.getCurrentWindowStartTime(), now);
            long elapsedMillis = elapsed.toMillis();
            long windowsPassed = elapsedMillis / windowMillis;

            if (windowsPassed == 1) {
                state.setPrevWindowCount(state.getCurrentWindowCount());
                state.setCurrentWindowCount(0);
                state.setCurrentWindowStartTime(state.getCurrentWindowStartTime().plus(winSize));
            } else if (windowsPassed > 1) {
                state.setPrevWindowCount(0);
                state.setCurrentWindowCount(0);
                long nowMillis = now.toEpochMilli();
                long alignedWindowStart = nowMillis - (nowMillis % windowMillis);
                state.setCurrentWindowStartTime(Instant.ofEpochMilli(alignedWindowStart));
            }

            long elapsedInsideCurrentWindow =
                    Duration.between(state.getCurrentWindowStartTime(), now).toMillis();

            double weight = (double) (windowMillis - elapsedInsideCurrentWindow) / windowMillis;
            double estimate = weight * state.getPrevWindowCount() + state.getCurrentWindowCount();

            Instant reset = state.getCurrentWindowStartTime().plus(winSize);
            resetAt.set(reset);
            retryAfter.set(Duration.between(now, reset));

            if (estimate + 1 > config.limit()) {
                accepted.set(false);
                finalEstimate.set(estimate);
                return state;
            }

            state.setCurrentWindowCount(state.getCurrentWindowCount() + 1);
            accepted.set(true);
            // Accept path: count increased by 1, so final estimate is `estimate + 1`.
            // BOTH branches now report the estimate AFTER this operation.
            finalEstimate.set(estimate + 1);

            return state;
        });

        // Consistent semantics: `finalEstimate` is the occupancy AFTER this request
        // (accepted → estimate+1; rejected → estimate). `floor` correctly yields how
        // many WHOLE additional requests still fit before the estimate reaches `limit`.
        int remainingRequests = Math.max(
                0,
                (int) Math.floor(config.limit() - finalEstimate.get())
        );

        return new RateLimitResult(
                accepted.get(),
                remainingRequests,
                retryAfter.get(),
                resetAt.get()
        );
    }

}
