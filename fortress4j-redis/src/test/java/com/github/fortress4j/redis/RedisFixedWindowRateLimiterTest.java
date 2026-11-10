package com.github.fortress4j.redis;

import com.github.fortress4j.models.RateLimitResult;
import com.github.fortress4j.config.FixedWindowConfig;
import com.redis.testcontainers.RedisContainer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class RedisFixedWindowRateLimiterTest {

    private static final long WINDOW_MS = 10_000L;
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

//    @Container
//    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.4-alpine");

    @Container
    static final RedisContainer REDIS = new RedisContainer("redis:8.8.4-alpine");

    static RedisClient client;
    static StatefulRedisConnection<String, String> connection;

    private final AtomicLong timeMs = new AtomicLong(1_000_000);

    @BeforeAll
    static void connect() {
        client = RedisClient.create(REDIS.getRedisURI());
        connection = client.connect();
    }

    @AfterAll
    static void disconnect() {
        connection.close();
        client.shutdown();
    }

    @BeforeEach
    void flush() {
        connection.sync().flushall();
    }

    private @NotNull RedisFixedWindowRateLimiter<String> limiter(int limit, long windowMs) {
        var config = new FixedWindowConfig(limit, Duration.ofMillis(windowMs));
        return new RedisFixedWindowRateLimiter<>(
                config, connection, "test:", String::valueOf,
                Clock.fixed(START, ZoneOffset.UTC), timeMs::get);
    }

    private void advance(long ms) {
        timeMs.addAndGet(ms);
    }

    @Test
    void allowsExactlyLimitThenDenies() {
        var rl = limiter(3, WINDOW_MS);

        assertEquals(2, rl.tryAcquire("a").remainingRequests());
        assertEquals(1, rl.tryAcquire("a").remainingRequests());
        assertEquals(0, rl.tryAcquire("a").remainingRequests());

        RateLimitResult denied = rl.tryAcquire("a");
        assertFalse(denied.allowed());
        assertEquals(0, denied.remainingRequests());
    }

    @Test
    void retryAfterIsTimeLeftInWindow() {
        var rl = limiter(1, WINDOW_MS);
        rl.tryAcquire("a"); // window starts at t0

        advance(4_000);
        RateLimitResult denied = rl.tryAcquire("a");

        assertFalse(denied.allowed());
        assertEquals(6_000L, denied.retryAfter());
    }

    @Test
    void resetAtIsEndOfWindow() {
        var rl = limiter(3, WINDOW_MS);
        assertEquals(START.plusSeconds(10), rl.tryAcquire("a").resetAt());
    }

    @Test
    void newWindowStartsAfterWindowEnds() {
        var rl = limiter(2, WINDOW_MS);
        rl.tryAcquire("a");
        rl.tryAcquire("a");
        assertFalse(rl.tryAcquire("a").allowed());

        advance(WINDOW_MS - 1);
        assertFalse(rl.tryAcquire("a").allowed()); // 1 ms early

        advance(1);
        assertEquals(1, rl.tryAcquire("a").remainingRequests()); // fresh window
    }

    @Test
    void deniedRequestsDoNotExtendTheWindow() {
        var rl = limiter(1, WINDOW_MS);
        rl.tryAcquire("a"); // window: t0 .. t0+10s

        advance(5_000);
        assertFalse(rl.tryAcquire("a").allowed()); // must not push the window forward

        advance(5_000); // t0 + 10s
        assertTrue(rl.tryAcquire("a").allowed());
    }

    @Test
    void boundaryBurstIsAllowedKnownFixedWindowTradeOff() {
        // Documents the classic weakness: up to 2x the limit around a window edge.
        var rl = limiter(3, WINDOW_MS);
        rl.tryAcquire("a"); // t=0 (1 of 3)
        advance(9_000);
        rl.tryAcquire("a"); // t=9s (2 of 3)
        rl.tryAcquire("a"); // t=9s (3 of 3)
        advance(1_000); // t=10s: new window

        int allowed = 0;
        for (int i = 0; i < 3; i++)
            if (rl.tryAcquire("a").allowed())
                allowed++;
        assertEquals(3, allowed); // 5 requests within ~1 second, limit is 3
    }

    @Test
    void keysAreIndependent() {
        var rl = limiter(1, WINDOW_MS);
        assertTrue(rl.tryAcquire("a").allowed());
        assertFalse(rl.tryAcquire("a").allowed());
        assertTrue(rl.tryAcquire("b").allowed());
    }

    @Test
    void keyGetsATtl() {
        var rl = limiter(3, WINDOW_MS);
        rl.tryAcquire("a");
        long pttl = connection.sync().pttl("test:a");
        assertTrue(pttl > 0 && pttl <= WINDOW_MS, "pttl was " + pttl);
    }

    @Test
    void survivesScriptCacheFlush() {
        var rl = limiter(3, WINDOW_MS);
        assertTrue(rl.tryAcquire("a").allowed());
        connection.sync().scriptFlush();
        assertTrue(rl.tryAcquire("a").allowed());
    }

    @Test
    void rejectsInvalidConfig() {
        assertThrows(IllegalArgumentException.class, () -> limiter(0, WINDOW_MS));
        assertThrows(IllegalArgumentException.class, () -> limiter(1, 0));
        var subMs = new FixedWindowConfig(3, Duration.ofNanos(1_500_000L)); // 1.5 ms
        assertThrows(IllegalArgumentException.class,
                () -> new RedisFixedWindowRateLimiter<String>(subMs, connection));
    }

    @Test
    void concurrentCallsNeverExceedLimit() throws Exception {
        int limit = 100, threads = 16, calls = 2_000;
        var rl = limiter(limit, 1_000_000); // time frozen: one long window

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < calls; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                if (rl.tryAcquire("shared").allowed())
                    allowed.incrementAndGet();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures)
            f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(limit, allowed.get());
    }
}
