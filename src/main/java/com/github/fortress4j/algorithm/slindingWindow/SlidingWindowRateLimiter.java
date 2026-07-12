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
            Instant now = clock.instant();
            if (state == null) {

                accepted.set(true);

                return new SlidingWindowState(now);

            }

             // Tells Us how many windows have passed??
            Duration elapsed = Duration.between(state.getCurrentWindowStartTime(),now);
            long elapsedMillis = elapsed.toMillis();
            long windowMillis = config.windowSize().toMillis();
            long windowPassed=elapsedMillis/windowMillis;


            if(windowPassed==1){

                state.setPrevWindowCount(state.getCurrentWindowCount());
                state.setCurrentWindowCount(0);
                state.setCurrentWindowStartTime(state.getCurrentWindowStartTime().plus(config.windowSize()));

            }
            else if(windowPassed>1){
                state.setPrevWindowCount(0);
                state.setCurrentWindowCount(0);
                long nowMillis = now.toEpochMilli();
                long alignedWindowStart = nowMillis-(nowMillis%windowMillis);
                Instant windowStart  = Instant.ofEpochMilli(alignedWindowStart);
                state.setCurrentWindowStartTime(windowStart);
            }

            long elapsedInsideCurrentWindow=Duration.between(state.getCurrentWindowStartTime(),now).toMillis();
            double weight = (double) (windowMillis - elapsedInsideCurrentWindow)/windowMillis;
            double estimateRequests = weight*(state.getPrevWindowCount()) + state.getCurrentWindowCount();

            if(estimateRequests>=config.limit()){
                accepted.set(false);
                return state;
            }
            else{
                state.setCurrentWindowCount(state.getCurrentWindowCount()+1);
                accepted.set(true);
            }


            return state;
        });
        return accepted.get();
    }
}
