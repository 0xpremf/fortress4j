package com.github.fortress4j.spring.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

@ConfigurationProperties(prefix = "fortress4j")
public record Fortress4jProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("memory") Backend backend,
        @DefaultValue("true") boolean headers,
        @DefaultValue("allow") OnError onError,
        @DefaultValue Redis redis,
        Map<String, Limiter> limiters) {

    public Fortress4jProperties {
        limiters = limiters == null ? Map.of() : limiters;
    }

    public enum Backend { MEMORY, REDIS }

    public enum OnError { ALLOW, DENY }

    public enum Algorithm { TOKEN_BUCKET, FIXED_WINDOW, SLIDING_WINDOW }

    public record Redis(
            @DefaultValue("redis://localhost:6379") String uri,
            @DefaultValue("fortress4j") String keyPrefix,
            @DefaultValue("100ms") Duration timeout) {}

    public record Limiter(
            @DefaultValue("token-bucket") Algorithm algorithm,
            Integer limit,
            Duration window,
            Integer capacity,
            Integer refillAmount,
            Duration refillInterval) {}
}