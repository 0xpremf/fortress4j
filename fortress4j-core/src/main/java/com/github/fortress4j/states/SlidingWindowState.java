package com.github.fortress4j.states;

import java.time.Instant;

public class SlidingWindowState {
    private int prevWindowCount;
    private int currentWindowCount;
    private Instant currentWindowStartTime;

    public SlidingWindowState(Instant windowStart) {
        this.currentWindowCount = 0;
        this.prevWindowCount = 0;
        this.currentWindowStartTime = windowStart;
    }


    public int getPrevWindowCount() {
        return prevWindowCount;
    }

    public void setPrevWindowCount(int prevWindowCount) {
        this.prevWindowCount = prevWindowCount;
    }

    public int getCurrentWindowCount() {
        return currentWindowCount;
    }

    public void setCurrentWindowCount(int currentWindowCount) {
        this.currentWindowCount = currentWindowCount;
    }

    public Instant getCurrentWindowStartTime() {
        return currentWindowStartTime;
    }

    public void setCurrentWindowStartTime(Instant currentWindowStartTime) {
        this.currentWindowStartTime = currentWindowStartTime;
    }
}
