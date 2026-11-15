package com.github.fortress4j.spring;

import com.github.fortress4j.models.RateLimiter;

import java.util.Map;
import java.util.Set;

/** Holds the named limiters built from configuration. */
public final class RateLimiterRegistry {

    public record Entry(RateLimiter<String> limiter, int limit) {}

    private final Map<String, Entry> entries;

    public RateLimiterRegistry(Map<String, Entry> entries) {
        this.entries = Map.copyOf(entries);
    }

    public Entry get(String name) {
        Entry entry = entries.get(name);
        if (entry == null) {
            throw new IllegalStateException("No rate limiter named '" + name
                    + "'. Define it under fortress4j.limiters." + name
                    + " (known: " + entries.keySet() + ")");
        }
        return entry;
    }

    public Set<String> names() {
        return entries.keySet();
    }
}