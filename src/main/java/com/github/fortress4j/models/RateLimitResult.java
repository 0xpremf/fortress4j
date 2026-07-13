package com.github.fortress4j.models;

import java.time.Duration;
import java.time.Instant;

public record RateLimitResult(

        boolean allowed,

        int remainingRequests,

        Duration retryAfter,

        Instant resetAt

) {

}
