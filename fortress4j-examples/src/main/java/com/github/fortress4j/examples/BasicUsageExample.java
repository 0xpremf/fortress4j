package com.github.fortress4j.examples;

import com.github.fortress4j.algorithm.fixedwindow.FixedWindowRateLimiter;
import com.github.fortress4j.algorithm.slidingWindow.SlidingWindowRateLimiter;
import com.github.fortress4j.algorithm.tokenBucket.TokenBucketRateLimiter;
import com.github.fortress4j.config.FixedWindowConfig;
import com.github.fortress4j.config.SlidingWindowConfig;
import com.github.fortress4j.config.TokenBucketConfig;
import com.github.fortress4j.models.RateLimitResult;
import com.github.fortress4j.storage.InMemoryStorage;

import java.time.Duration;

public class BasicUsageExample {

    public static void main(String[] args) {
        System.out.println("=== Fortress4j Rate Limiting Examples ===");

        // 1. Fixed Window Rate Limiter: 5 requests per 10 seconds
        FixedWindowConfig fixedConfig = new FixedWindowConfig(5, Duration.ofSeconds(10));
        FixedWindowRateLimiter<String> fixedLimiter = new FixedWindowRateLimiter<>(fixedConfig, new InMemoryStorage<>());

        System.out.println("\n--- Fixed Window Limiter ---");
        for (int i = 1; i <= 6; i++) {
            RateLimitResult result = fixedLimiter.tryAcquire("user-1");
            System.out.printf("Request %d: allowed=%b, remaining=%d, retryAfter=%dms%n",
                    i, result.allowed(), result.remainingRequests(), result.retryAfter());
        }

        // 2. Token Bucket Rate Limiter: capacity of 3 tokens, refilling 1 token every second
        TokenBucketConfig tokenConfig = new TokenBucketConfig(3, 1, 1_000_000_000L);
        TokenBucketRateLimiter<String> tokenLimiter = new TokenBucketRateLimiter<>(
                tokenConfig,
                new InMemoryStorage<>(),
                System::nanoTime
        );

        System.out.println("\n--- Token Bucket Limiter ---");
        for (int i = 1; i <= 4; i++) {
            RateLimitResult result = tokenLimiter.tryAcquire("user-2");
            System.out.printf("Request %d: allowed=%b, remaining=%d, retryAfter=%dms%n",
                    i, result.allowed(), result.remainingRequests(), result.retryAfter());
        }

        // 3. Sliding Window Rate Limiter: 4 requests per 5 seconds
        SlidingWindowConfig slidingConfig = new SlidingWindowConfig(4, Duration.ofSeconds(5));
        SlidingWindowRateLimiter<String> slidingLimiter = new SlidingWindowRateLimiter<>(slidingConfig, new InMemoryStorage<>());

        System.out.println("\n--- Sliding Window Limiter ---");
        for (int i = 1; i <= 5; i++) {
            RateLimitResult result = slidingLimiter.tryAcquire("user-3");
            System.out.printf("Request %d: allowed=%b, remaining=%d, retryAfter=%dms%n",
                    i, result.allowed(), result.remainingRequests(), result.retryAfter());
        }
    }
}

