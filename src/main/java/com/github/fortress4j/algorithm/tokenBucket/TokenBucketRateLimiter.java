package com.github.fortress4j.algorithm.tokenBucket;

import com.github.fortress4j.config.TokenBucketConfig;
import com.github.fortress4j.models.RateLimitResult;
import com.github.fortress4j.models.RateLimiter;
import com.github.fortress4j.states.TokenBucketState;
import com.github.fortress4j.storage.InMemoryStorage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

public class TokenBucketRateLimiter<K> implements RateLimiter<K> {

    private final TokenBucketConfig config;
    private final InMemoryStorage<K, TokenBucketState> storage;
   // private AtomicInteger refillCount = new AtomicInteger(0); doesn't need it left it for the learning purpose
    private final Clock clock;
    private final LongSupplier ticker;

    public TokenBucketRateLimiter(TokenBucketConfig config, InMemoryStorage<K, TokenBucketState> storage, LongSupplier ticker) {
        this.config = config;
        this.storage = storage;
        this.ticker = ticker;
        this.clock = Clock.systemUTC();
    }

    public TokenBucketRateLimiter(TokenBucketConfig config, InMemoryStorage<K, TokenBucketState> storage, Clock clock, LongSupplier ticker) {
        this.config = Objects.requireNonNull(config);
        this.storage = Objects.requireNonNull(storage);
        this.clock = Objects.requireNonNull(clock);
        this.ticker = Objects.requireNonNull(ticker);

        if(config.bucketSize() <= 0 || config.refillAmount() <=0 || config.refillIntervalNanos()<=0) {
            throw new IllegalArgumentException(
                    "Bucket size, refill amount, and refill interval must be positive"
            );
        }
    }


    @Override
    public RateLimitResult tryAcquire(K key) {
        Objects.requireNonNull(key, () -> "key can't be null");
        final long nowNanos = ticker.getAsLong();

        final Instant now = clock.instant();

        AtomicReference<RateLimitResult> result = new AtomicReference<>();

        storage.compute(key, (k, existingState) ->  {

            TokenBucketState state = existingState;

            if (state==null){
                state = new TokenBucketState(config.bucketSize(), nowNanos);
            }

            long currentTokens = state.getCurrentTokens();
            long lastRefillTime = state.getLastRefillTime();

            long elapsedNanos = nowNanos - lastRefillTime;

            if (elapsedNanos<0){
                throw new IllegalStateException(
                  "Ticker moved backwards or the stored state is invalid."
                );
            }

            long refillIntervalNanos = config.refillIntervalNanos();
            long refillAmount = config.refillAmount();
            long bucketSize = config.bucketSize();

            long intervalElapsed = elapsedNanos/refillIntervalNanos;

            long tokenNeeded = bucketSize- currentTokens;
            long intervalsNeeded = tokenNeeded/ refillAmount;

            if(tokenNeeded% refillAmount !=0){
                intervalsNeeded++;
            }

            long updatedLastRefillTime;
            long refilledTokens=bucketSize;

            if(tokenNeeded==0 || intervalElapsed>=intervalsNeeded){
                refillAmount = bucketSize;
                updatedLastRefillTime = nowNanos;
            }

            else {
                long tokensToAdd = intervalElapsed*refillAmount;

                updatedLastRefillTime = lastRefillTime + intervalElapsed*refillIntervalNanos;

                refilledTokens = tokensToAdd + currentTokens;

            }

            boolean allowed = refilledTokens > 0;

            long remainingTokens = allowed? refilledTokens-1 : 0;

            TokenBucketState updatedState = new TokenBucketState(remainingTokens, updatedLastRefillTime);

            Duration retryAfter = Duration.ZERO;

            if (!allowed) {
                long elapsedSinceBoundary =
                        nowNanos - updatedLastRefillTime;

                long nanosUntilNextRefill =
                        refillIntervalNanos - (elapsedSinceBoundary % refillIntervalNanos);

                retryAfter = Duration.ofNanos(nanosUntilNextRefill);
            }

            // Estimate when the bucket will become completely full again.
            long missingTokens = bucketSize - remainingTokens;

            long intervalsUntilFull = missingTokens / refillAmount;

            if (missingTokens % refillAmount != 0) {
                intervalsUntilFull++;
            }

            long nanosUntilFull;

            if (intervalsUntilFull == 0) {
                nanosUntilFull = 0;
            } else if (intervalsUntilFull
                    > Long.MAX_VALUE / refillIntervalNanos) {
                // Avoid overflowing the duration calculation.
                nanosUntilFull = Long.MAX_VALUE;
            } else {
                long totalRefillNanos =
                        intervalsUntilFull * refillIntervalNanos;

                long elapsedSinceBoundary =
                        nowNanos - updatedLastRefillTime;

                nanosUntilFull = Math.max(
                        0,
                        totalRefillNanos - elapsedSinceBoundary
                );
            }

            Instant resetAt;

            try {
                resetAt = now.plusNanos(nanosUntilFull);
            } catch (RuntimeException ex) {
                // Extremely large durations cannot always fit in an Instant.
                resetAt = Instant.MAX;
            }

            result.set(new RateLimitResult(
                    allowed,
                    remainingTokens,
                    retryAfter,
                    resetAt
            ));

            return updatedState;
        });

        return result.get();
    }


}
