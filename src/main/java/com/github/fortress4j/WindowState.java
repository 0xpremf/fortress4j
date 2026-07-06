package com.github.fortress4j;

import java.time.Duration;
import java.time.Instant;

public class WindowState {
    private int requestCount;
    private Instant windowEnd;

    public WindowState(Duration windowDuration) {

        requestCount = 1;
        this.windowEnd = Instant.now().plus(windowDuration);
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

    public void incerementRequestCount() {
        requestCount++;
    }

    public void resetState(Duration windowDuration) {
        requestCount = 1;
        windowEnd = Instant.now().plus(windowDuration);
    }





}
