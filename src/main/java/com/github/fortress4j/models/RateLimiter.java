package com.github.fortress4j.models;

public interface RateLimiter {
    Boolean tryAcquire(String key);
}
