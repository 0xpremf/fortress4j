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
        AtomicReference<Double> estimatedRequests = new AtomicReference<>(0.0);
        AtomicReference<Instant> resetAt = new AtomicReference<>();
        AtomicReference<Duration> retryAfter = new AtomicReference<>(Duration.ZERO);

        storage.compute(key, (userKey, state) -> {

            Instant now = clock.instant();
            long windowMillis = config.windowSize().toMillis();

            if (state == null) {
                state = new SlidingWindowState(now);
            }

            // How many windows have passed?
            Duration elapsed = Duration.between(
                    state.getCurrentWindowStartTime(),
                    now
            );

            long elapsedMillis = elapsed.toMillis();
            long windowsPassed = elapsedMillis / windowMillis;

            if (windowsPassed == 1) {

                state.setPrevWindowCount(state.getCurrentWindowCount());
                state.setCurrentWindowCount(0);
                state.setCurrentWindowStartTime(
                        state.getCurrentWindowStartTime().plus(config.windowSize())
                );

            } else if (windowsPassed > 1) {

                state.setPrevWindowCount(0);
                state.setCurrentWindowCount(0);

                long nowMillis = now.toEpochMilli();
                long alignedWindowStart = nowMillis - (nowMillis % windowMillis);

                state.setCurrentWindowStartTime(
                        Instant.ofEpochMilli(alignedWindowStart)
                );
            }

            long elapsedInsideCurrentWindow =
                    Duration.between(
                            state.getCurrentWindowStartTime(),
                            now
                    ).toMillis();

            double weight =
                    (double) (windowMillis - elapsedInsideCurrentWindow)
                            / windowMillis;

            double estimate =
                    weight * state.getPrevWindowCount()
                            + state.getCurrentWindowCount();

            estimatedRequests.set(estimate);

            if (estimate >= config.limit()) {

                accepted.set(false);

                Instant reset =
                        state.getCurrentWindowStartTime().plus(config.windowSize());

                resetAt.set(reset);
                retryAfter.set(Duration.between(now, reset));

                return state;
            }

            state.setCurrentWindowCount(
                    state.getCurrentWindowCount() + 1
            );

            estimatedRequests.set(estimate + 1);
            accepted.set(true);

            Instant reset =
                    state.getCurrentWindowStartTime().plus(config.windowSize());

            resetAt.set(reset);
            retryAfter.set(Duration.between(now, reset));

            return state;
        });

        int remainingRequests = Math.max(
                0,
                config.limit() - (int) Math.ceil(estimatedRequests.get())
        );

        return new RateLimitResult(
                accepted.get(),
                remainingRequests,
                retryAfter.get(),
                resetAt.get()
        );
    }

}
