package com.github.fortress4j.models;

public record RateLimitResult(
        boolean allowed,
        long retryTime,
        long resetTime,
        long tokensRemaining
) { }
