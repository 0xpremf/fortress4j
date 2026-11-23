package com.github.fortress4j.spring.autoconfigure;

import com.github.fortress4j.config.FixedWindowConfig;
import com.github.fortress4j.config.SlidingWindowConfig;
import com.github.fortress4j.config.TokenBucketConfig;
import com.github.fortress4j.redis.RedisFixedWindowRateLimiter;
import com.github.fortress4j.redis.RedisSlidingWindowRateLimiter;
import com.github.fortress4j.redis.RedisTokenBucketRateLimiter;
import com.github.fortress4j.spring.RateLimiterRegistry;
import io.lettuce.core.api.StatefulRedisConnection;

import static com.github.fortress4j.spring.autoconfigure.InMemoryLimiterFactory.required;
import static com.github.fortress4j.spring.autoconfigure.InMemoryLimiterFactory.tokenBucketConfig;

public class RedisLimiterFactory implements LimiterFactory {

    private final StatefulRedisConnection<String, String> connection;
    private final String keyPrefix;
    public RedisLimiterFactory(StatefulRedisConnection<String,String> connection, String keyPrefix){
        this.connection=connection;
        this.keyPrefix=keyPrefix;
    }


    @Override
    public RateLimiterRegistry.Entry create(String name, Fortress4jProperties.Limiter spec) {
        String prefix = keyPrefix + ":" + name + ":";
        return switch (spec.algorithm()) {
            case TOKEN_BUCKET -> new RateLimiterRegistry.Entry(
                    new RedisTokenBucketRateLimiter<String>(
                            tokenBucketConfig(name, spec), connection, prefix, k -> k),
                    spec.capacity());
            case FIXED_WINDOW -> new RateLimiterRegistry.Entry(
                    new RedisFixedWindowRateLimiter<String>(
                            new FixedWindowConfig(required(spec.limit(), name, "limit"),
                                    required(spec.window(), name, "window")),
                            connection, prefix, k -> k),
                    spec.limit());
            case SLIDING_WINDOW -> new RateLimiterRegistry.Entry(
                    new RedisSlidingWindowRateLimiter<String>(
                            new SlidingWindowConfig(required(spec.limit(), name, "limit"),
                                    required(spec.window(), name, "window")),
                            connection, prefix, k -> k),
                    spec.limit());
        };
    }
}
