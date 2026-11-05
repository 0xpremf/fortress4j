package com.github.fortress4j.states;

public class TokenBucketState {
    private long currentTokens;
    private long lastRefillTime;

    public TokenBucketState(long currentTokens, long lastRefillTime) {
        this.currentTokens = currentTokens;
        this.lastRefillTime = lastRefillTime;
    }

    public long getCurrentTokens() {
        return currentTokens;
    }

    public void setCurrentTokens(long currentTokens) {
        this.currentTokens = currentTokens;
    }

    public long getLastRefillTime() {
        return lastRefillTime;
    }

    public void setLastRefillTime(long lastRefillTime) {
        this.lastRefillTime = lastRefillTime;
    }
}
