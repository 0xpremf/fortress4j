package com.github.fortress4j.models;

import java.time.Instant;

public record RateLimitResult(
        boolean allowed,
        long remainingRequests,
        long retryAfter,
        Instant resetAt
) {
}
