package com.github.fortress4j.redis;

import com.github.fortress4j.config.TokenBucketConfig;
import com.github.fortress4j.models.RateLimitResult;
import com.github.fortress4j.models.RateLimiter;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Redis-backed token bucket.
 *
 * <p>The refill and consume decision runs entirely inside
 * {@code token_bucket.lua}, so the bucket stays consistent across threads and
 * across application instances. State is a single hash per key
 * ({@code tokens}, {@code last_refill}); nothing grows with request volume.
 */
public class RedisTokenBucketRateLimiter<K> implements RateLimiter<K> {

    private static final LuaScript SCRIPT = LuaScript.fromResource("/lua/token_bucket.lua");
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long MAX_TTL_MS = Duration.ofDays(365).toMillis();

    private final RedisCommands<String, String> commands;
    private final String keyPrefix;
    private final Function<K, String> keyMapper;
    private final Clock clock;
    private final LongSupplier serverTimeOverrideMillis;   // -1 = use Redis TIME

    private final String bucketSizeArg;
    private final String refillAmountArg;
    private final String intervalMsArg;

    public RedisTokenBucketRateLimiter(TokenBucketConfig config,
                                       StatefulRedisConnection<String, String> connection) {
        this(config, connection, "fortress4j:tb:", String::valueOf);
    }

    public RedisTokenBucketRateLimiter(TokenBucketConfig config,
                                       StatefulRedisConnection<String, String> connection,
                                       String keyPrefix,
                                       Function<K, String> keyMapper) {
        this(config, connection, keyPrefix, keyMapper, Clock.systemUTC(), () -> -1L);
    }

    // Package-private: tests inject a fake time source (the Lua script accepts it).
    RedisTokenBucketRateLimiter(TokenBucketConfig config,
                                StatefulRedisConnection<String, String> connection,
                                String keyPrefix,
                                Function<K, String> keyMapper,
                                Clock clock,
                                LongSupplier serverTimeOverrideMillis) {
        Objects.requireNonNull(config, "config cannot be null");
        this.commands = Objects.requireNonNull(connection, "connection cannot be null").sync();
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix cannot be null");
        this.keyMapper = Objects.requireNonNull(keyMapper, "keyMapper cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.serverTimeOverrideMillis = Objects.requireNonNull(serverTimeOverrideMillis, "serverTimeOverrideMillis cannot be null");

        long bucketSize = config.bucketSize();
        long refillAmount = config.refillAmount();
        long intervalNanos = config.refillIntervalNanos();

        if (bucketSize <= 0 || refillAmount <= 0 || intervalNanos <= 0) {
            throw new IllegalArgumentException(
                    "Bucket size, refill amount, and refill interval must be positive");
        }
        if (bucketSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Bucket capacity cannot exceed Integer.MAX_VALUE (" + Integer.MAX_VALUE + "), got: " + bucketSize
            );
        }
        if (intervalNanos % NANOS_PER_MILLI != 0) {
            throw new IllegalArgumentException(
                    "Redis limiter needs a refill interval in whole milliseconds");
        }

        long intervalMs = intervalNanos / NANOS_PER_MILLI;

        // TTL in the script is max(1000, timeToFull + 2 * interval). Reject
        // horizons Redis/Lua cannot represent sanely instead of silently
        // creating keys that outlive the system.
        long intervalsToFull = bucketSize / refillAmount
                + (bucketSize % refillAmount == 0 ? 0 : 1);
        if (intervalMs > MAX_TTL_MS / (intervalsToFull + 2)) {
            throw new IllegalArgumentException(
                    "Time to refill the bucket is too long for Redis (limit: 365 days)");
        }

        this.bucketSizeArg = String.valueOf(bucketSize);
        // A refill bigger than the bucket can only ever fill it, so clamping
        // keeps the Lua arithmetic small without changing behaviour.
        this.refillAmountArg = String.valueOf(Math.min(refillAmount, bucketSize));
        this.intervalMsArg = String.valueOf(intervalMs);
    }

    @Override
    public RateLimitResult tryAcquire(K key) {
        Objects.requireNonNull(key, "key can't be null");
        String redisKey = keyPrefix + keyMapper.apply(key);
        Instant now = clock.instant();

        List<Object> r = SCRIPT.run(
                commands,
                new String[]{redisKey},
                new String[]{
                        bucketSizeArg,
                        refillAmountArg,
                        intervalMsArg,
                        String.valueOf(serverTimeOverrideMillis.getAsLong())
                });

        boolean allowed = ((Long) r.get(0)) == 1L;
        long remainingTokens = (Long) r.get(1);
        long retryAfterMs = (Long) r.get(2);
        long resetAfterMs = (Long) r.get(3);

        return new RateLimitResult(
                allowed,
                remainingTokens,
                retryAfterMs,
                resetAt(now, resetAfterMs));
    }

    private static Instant resetAt(Instant now, long millis) {
        try {
            return now.plusMillis(millis);
        } catch (RuntimeException ex) {
            return Instant.MAX;
        }
    }
}
