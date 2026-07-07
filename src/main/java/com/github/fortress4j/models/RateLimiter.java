package com.github.fortress4j.models;

public interface RateLimiter<K> {
    Boolean tryAcquire(K key);
}
