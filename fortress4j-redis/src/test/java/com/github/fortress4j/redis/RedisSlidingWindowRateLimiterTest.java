package com.github.fortress4j.redis;

import com.github.fortress4j.models.RateLimitResult;
import com.github.fortress4j.config.SlidingWindowConfig;
import com.redis.testcontainers.RedisContainer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
class RedisSlidingWindowRateLimiterTest {

    private static final long WINDOW_MS = 10_000L;
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Container
    static final RedisContainer REDIS = new RedisContainer("redis:8.8-alpine");

    static RedisClient client;
    static StatefulRedisConnection<String, String> connection;

    // 1_000_000 is exactly the start of a 10 s window (index 100).
    private final AtomicLong timeMs = new AtomicLong(1_000_000);

    @BeforeAll
    static void connect() {
        client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
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

    private RedisSlidingWindowRateLimiter<String> limiter(int limit, long windowMs) {
        var config = new SlidingWindowConfig(limit, Duration.ofMillis(windowMs));
        return new RedisSlidingWindowRateLimiter<>(
                config, connection, "test:", String::valueOf,
                Clock.fixed(START, ZoneOffset.UTC), timeMs::get);
    }

    private void advance(long ms) { timeMs.addAndGet(ms); }

    private int countAllowed(RedisSlidingWindowRateLimiter<String> rl, int attempts) {
        int allowed = 0;
        for (int i = 0; i < attempts; i++) if (rl.tryAcquire("a").allowed()) allowed++;
        return allowed;
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
    void retryAfterIsBoundaryWhenCurrentWindowIsFull() {
        var rl = limiter(1, WINDOW_MS);
        rl.tryAcquire("a");                          // t = 0 of the window

        advance(4_000);
        RateLimitResult denied = rl.tryAcquire("a");

        assertFalse(denied.allowed());
        assertEquals(6_000L, denied.retryAfter());   // milliseconds
    }

    @Test
    void previousWindowCountsProportionally() {
        var rl = limiter(10, WINDOW_MS);
        assertEquals(10, countAllowed(rl, 10));      // fill window 0

        advance(WINDOW_MS);                          // start of window 1: previous weighs 100%
        assertFalse(rl.tryAcquire("a").allowed());

        advance(5_000);                              // halfway: previous weighs 50% -> 5 slots free
        assertEquals(5, countAllowed(rl, 10));
    }

    @Test
    void retryAfterIsExactWhenRoomOpensInsideTheWindow() {
        var rl = limiter(10, WINDOW_MS);
        countAllowed(rl, 10);
        advance(WINDOW_MS + 5_000);
        assertEquals(5, countAllowed(rl, 10));       // cur = 5, prev = 10, 5000 ms in

        RateLimitResult denied = rl.tryAcquire("a");
        assertFalse(denied.allowed());
        // At +1 ms: floor(10 * 4999 / 10000) = 4, and 4 + 5 < 10, so a slot opens.
        assertEquals(1L, denied.retryAfter());       // milliseconds
    }

    @Test
    void noBoundaryBurstUnlikeFixedWindow() {
        var rl = limiter(3, WINDOW_MS);
        advance(9_000);
        assertEquals(3, countAllowed(rl, 3));        // 3 requests at 9 s

        advance(1_000);                              // 10 s: new window begins
        assertFalse(rl.tryAcquire("a").allowed());   // a fixed window would allow 3 more here
    }

    @Test
    void skippingWholeWindowsForgetsOldTraffic() {
        var rl = limiter(2, WINDOW_MS);
        countAllowed(rl, 2);

        advance(2 * WINDOW_MS);                      // two windows later: nothing is carried over
        assertEquals(2, countAllowed(rl, 2));
    }

    @Test
    void resetAtIsWhenEverythingHasAgedOut() {
        var rl = limiter(3, WINDOW_MS);
        // 1 request at the start of a window: it stops counting at the end of the NEXT window.
        assertEquals(START.plusSeconds(20), rl.tryAcquire("a").resetAt());
    }

    @Test
    void keysAreIndependent() {
        var rl = limiter(1, WINDOW_MS);
        assertTrue(rl.tryAcquire("a").allowed());
        assertFalse(rl.tryAcquire("a").allowed());
        assertTrue(rl.tryAcquire("b").allowed());
    }

    @Test
    void keyGetsATtlOfTwoWindows() {
        var rl = limiter(3, WINDOW_MS);
        rl.tryAcquire("a");
        long pttl = connection.sync().pttl("test:a");
        assertTrue(pttl > WINDOW_MS && pttl <= 2 * WINDOW_MS, "pttl was " + pttl);
    }

    @Test
    void survivesScriptCacheFlush() {
        var rl = limiter(3, WINDOW_MS);
        assertTrue(rl.tryAcquire("a").allowed());
        connection.sync().scriptFlush();
        assertTrue(rl.tryAcquire("a").allowed());
    }

    @Test
    void rejectsConfigsRedisCannotRepresent() {
        var subMs = new SlidingWindowConfig(3, Duration.ofNanos(1_500_000));
        assertThrows(IllegalArgumentException.class,
                () -> new RedisSlidingWindowRateLimiter<String>(subMs, connection));

        var tooLong = new SlidingWindowConfig(3, Duration.ofDays(181));
        assertThrows(IllegalArgumentException.class,
                () -> new RedisSlidingWindowRateLimiter<String>(tooLong, connection));

        var tooPrecise = new SlidingWindowConfig(Integer.MAX_VALUE, Duration.ofDays(30));
        assertThrows(IllegalArgumentException.class,
                () -> new RedisSlidingWindowRateLimiter<String>(tooPrecise, connection));
    }

    @Test
    void concurrentCallsNeverExceedLimit() throws Exception {
        int limit = 100, threads = 16, calls = 2_000;
        var rl = limiter(limit, 1_000_000);          // time frozen inside one window

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < calls; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                if (rl.tryAcquire("shared").allowed()) allowed.incrementAndGet();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(limit, allowed.get());
    }
}