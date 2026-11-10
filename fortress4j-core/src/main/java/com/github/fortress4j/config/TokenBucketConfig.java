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
        if (refillIntervalNanos <= 0) {
            throw new IllegalArgumentException("refillIntervalNanos must be greater than 0");
        }
    }

    public long fullRefillNanos() {
        long intervals = (bucketSize + refillAmount - 1) / refillAmount;   // ceil
        return intervals > Long.MAX_VALUE / refillIntervalNanos
                ? Long.MAX_VALUE
                : intervals * refillIntervalNanos;
    }
}
