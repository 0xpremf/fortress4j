package com.github.fortress4j.models;

public interface RateLimiter {
    RateLimitResult tryAcquire(String key);
}
