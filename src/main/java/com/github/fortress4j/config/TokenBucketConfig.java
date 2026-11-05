package com.github.fortress4j.config;

public record TokenBucketConfig(
        long bucketSize,
        long refillAmount,
        long refillIntervalNanos


) implements RateLimiterConfig {

    public TokenBucketConfig {
        if (bucketSize <= 0) {
            throw new IllegalArgumentException("bucketSize must be greater than 0");
        }
        if (refillAmount <= 0) {
            throw new IllegalArgumentException("refillAmount must be greater than 0");
        }

    }
}
