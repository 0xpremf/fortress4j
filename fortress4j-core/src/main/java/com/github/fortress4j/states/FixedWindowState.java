package com.github.fortress4j.states;

import java.time.Duration;
import java.time.Instant;

public class FixedWindowState {
    private int requestCount;
    private Instant windowEnd;

    public FixedWindowState(Duration windowDuration, Instant now) {
        this.requestCount = 0;
        this.windowEnd = now.plus(windowDuration);
    }

    public int getRequestCount() {
        return requestCount;
    }

    public void setRequestCount(int requestCount) {
        this.requestCount = requestCount;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(Instant windowEnd) {
        this.windowEnd = windowEnd;
    }

    public void incrementRequestCount() {
        requestCount++;
    }

    public void resetState(Duration windowDuration, Instant now) {
        this.requestCount = 0;
        this.windowEnd = now.plus(windowDuration);
    }

    public void resetState(Duration windowDuration) {
        resetState(windowDuration, Instant.now());
    }
}
