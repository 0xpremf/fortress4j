package com.github.fortress4j.redis;

import com.github.fortress4j.models.RateLimitResult;
import com.github.fortress4j.models.RateLimiter;
import com.github.fortress4j.config.FixedWindowConfig;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;

public class RedisFixedWindowRateLimiter<K> implements RateLimiter<K> {

    private static final LuaScript SCRIPT = LuaScript.fromResource("/lua/fixed_window.lua");
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final RedisCommands<String, String> commands;
    private final String keyPrefix;
    private final Function<K, String> keyMapper;
    private final Clock clock;
    private final LongSupplier serverTimeOverrideMillis;   // -1 = use Redis TIME

    private final String limitArg;
    private final String windowMsArg;

    public RedisFixedWindowRateLimiter(FixedWindowConfig config,
                                       StatefulRedisConnection<String, String> connection) {
        this(config, connection, "fortress4j:fw:", String::valueOf);
    }

    public RedisFixedWindowRateLimiter(FixedWindowConfig config,
                                       StatefulRedisConnection<String, String> connection,
                                       String keyPrefix,
                                       Function<K, String> keyMapper) {
        this(config, connection, keyPrefix, keyMapper, Clock.systemUTC(), () -> -1L);
    }

    // Package-private: tests inject a fake time source (the Lua script accepts it).
    RedisFixedWindowRateLimiter(FixedWindowConfig config,
                                StatefulRedisConnection<String, String> connection,
                                String keyPrefix,
                                Function<K, String> keyMapper,
                                Clock clock,
                                LongSupplier serverTimeOverrideMillis) {
        Objects.requireNonNull(config);
        this.commands = Objects.requireNonNull(connection).sync();
        this.keyPrefix = Objects.requireNonNull(keyPrefix);
        this.keyMapper = Objects.requireNonNull(keyMapper);
        this.clock = Objects.requireNonNull(clock);
        this.serverTimeOverrideMillis = Objects.requireNonNull(serverTimeOverrideMillis);

        if (config.limit() <= 0 || config.windowSize().toNanos()<= 0) {
            throw new IllegalArgumentException("Limit and window must be positive");
        }
        if (config.windowSize().toNanos() % NANOS_PER_MILLI != 0) {
            throw new IllegalArgumentException(
                    "Redis limiter needs a window in whole milliseconds");
        }

        this.limitArg = String.valueOf(config.limit());
        this.windowMsArg = String.valueOf(config.windowSize().toMillis());
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
                        limitArg,
                        windowMsArg,
                        windowMsArg,                               // TTL = one window
                        String.valueOf(serverTimeOverrideMillis.getAsLong())
                });

        boolean allowed = ((Long) r.get(0)) == 1L;
        long remaining = (Long) r.get(1);
        long retryAfterMs = (Long) r.get(2);
        long resetAfterMs = (Long) r.get(3);

        return new RateLimitResult(
                allowed,
                (int) remaining,                  // safe: limit is capped at Integer.MAX_VALUE
                Duration.ofMillis(retryAfterMs),
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