package com.github.fortress4j.spring.autoconfigure;

import com.github.fortress4j.spring.RateLimiterRegistry;

public interface LimiterFactory {
    RateLimiterRegistry.Entry create(String name, Fortress4jProperties.Limiter spec);
}
