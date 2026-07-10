package com.github.fortress4j.algorithm.slindingWindow;

import com.github.fortress4j.config.SlidingWindowConfig;
import com.github.fortress4j.models.RateLimiter;
import com.github.fortress4j.states.SlidingWindowState;
import com.github.fortress4j.storage.InMemoryStorage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

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
    public Boolean tryAcquire(K key) {
        AtomicBoolean accepted = new AtomicBoolean(false);
        storage.compute(key, (userkey, state)->{

            if (state == null) {

                accepted.set(true);
                Instant now = clock.instant();
                return new SlidingWindowState(now);

            }

            Instant now = Instant.now();
            Duration elapsed = Duration.between(state.getCurrentWindowStartTime(),now);

            long elapsedMillis = elapsed.toMillis();
            long windowMillis = config.windowSize().toMillis();
            long windowPassed=windowMillis/elapsedMillis;
            if(windowPassed==0){
                return state;
            }
            else if(windowPassed==1){

                state.setPrevWindowCount(state.getCurrentWindowCount());
                state.setCurrentWindowCount(0);
                state.setCurrentWindowStartTime(state.getCurrentWindowStartTime().plus(config.windowSize()));
                return state;
            }
            else{
                state.setPrevWindowCount(0);
                state.setCurrentWindowCount(0);
                 state.setCurrentWindowStartTime(now);
            }








            return state;
        });
    }
}
