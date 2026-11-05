package com.github.fortress4j.models;

public interface RateLimiter<K> {
    RateLimitResult tryAcquire(K key);
}
