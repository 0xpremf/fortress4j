package com.github.fortress4j;

import java.time.Duration;
import java.time.Instant;

public class WindowState {
    private int requestCount;
    private Instant windowEnd;

    public WindowState(int i, Duration duration) {

    this.requestCount = i;
        this.windowEnd = Instant.now().plus(duration);
    }

    public void setWindowEnd(Instant windowEnd) {
        this.windowEnd = windowEnd;
    }

    public void setRequestCount(int requestCount) {
        this.requestCount = requestCount;
    }


}
