package com.github.fortress4j.states;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public class WindowState {
    private int requestCount;
    private Instant windowEnd;

    public WindowState(Duration windowDuration, Instant now) {

        requestCount = 0;
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

    public void resetState(Duration windowDuration) {
        requestCount = 0;
        windowEnd = Instant.now().plus(windowDuration);
    }





}
