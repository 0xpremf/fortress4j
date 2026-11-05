package com.github.fortress4j.algorithm.TokenBucket;

import com.github.fortress4j.config.TokenBucketConfig;
import com.github.fortress4j.models.RateLimitResult;
import com.github.fortress4j.storage.InMemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    private static final long SEC = 1_000_000_000L;
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private AtomicLong ticker;
    private Clock clock;

    @BeforeEach
    void setUp() {
        ticker = new AtomicLong(1_000 * SEC);
        clock = Clock.fixed(START, ZoneOffset.UTC);
    }

    private TokenBucketRateLimiter<String> limiter(long bucket, long refillAmount, long intervalNanos) {
        TokenBucketConfig config = new TokenBucketConfig(bucket, refillAmount, intervalNanos);
        return new TokenBucketRateLimiter<>(config, new InMemoryStorage<>(), clock, ticker::get);
    }

    private void advance(long nanos) {
        ticker.addAndGet(nanos);
    }

    @Test
    void freshBucketAllowsExactlyBucketSizeThenDenies() {
        var rl = limiter(3, 1, SEC);

        assertEquals(2, rl.tryAcquire("a").remainingRequests());
        assertEquals(1, rl.tryAcquire("a").remainingRequests());
        assertEquals(0, rl.tryAcquire("a").remainingRequests());

        RateLimitResult denied = rl.tryAcquire("a");
        assertFalse(denied.allowed());
        assertEquals(0, denied.remainingRequests());
        assertEquals(Duration.ofSeconds(1), denied.retryAfter());
    }

    @Test
    void allowsAgainAfterOneInterval() {
        var rl = limiter(3, 1, SEC);
        for (int i = 0; i < 3; i++) rl.tryAcquire("a");
        assertFalse(rl.tryAcquire("a").allowed());

        advance(SEC);

        assertTrue(rl.tryAcquire("a").allowed());
        assertFalse(rl.tryAcquire("a").allowed());
    }

    @Test
    void doesNotRefillBeforeIntervalCompletes() {
        var rl = limiter(1, 1, SEC);
        assertTrue(rl.tryAcquire("a").allowed());

        advance(SEC - 1);
        assertFalse(rl.tryAcquire("a").allowed());

        advance(1);
        assertTrue(rl.tryAcquire("a").allowed());
    }

    @Test
    void preservesPartialIntervalProgress() {
        var rl = limiter(3, 1, SEC);
        for (int i = 0; i < 3; i++) rl.tryAcquire("a");

        advance(SEC + SEC / 2);                           // t = 1.5s
        assertTrue(rl.tryAcquire("a").allowed());         // boundary moves to 1.0s

        RateLimitResult denied = rl.tryAcquire("a");
        assertFalse(denied.allowed());
        assertEquals(Duration.ofMillis(500), denied.retryAfter());

        advance(SEC / 2);                                 // t = 2.0s
        assertTrue(rl.tryAcquire("a").allowed());
    }

    @Test
    void multipleIntervalsAddMultipleRequests() {
        var rl = limiter(5, 1, SEC);
        for (int i = 0; i < 5; i++) rl.tryAcquire("a");

        advance(3 * SEC);

        assertEquals(2, rl.tryAcquire("a").remainingRequests());
    }

    @Test
    void refillAmountGreaterThanOne() {
        var rl = limiter(10, 4, SEC);
        for (int i = 0; i < 10; i++) rl.tryAcquire("a");

        advance(SEC);

        assertEquals(3, rl.tryAcquire("a").remainingRequests());
    }

    @Test
    void neverExceedsBucketSize() {
        var rl = limiter(3, 1, SEC);
        rl.tryAcquire("a");

        advance(3600 * SEC);

        assertEquals(2, rl.tryAcquire("a").remainingRequests());
    }

    @Test
    void idleTimeOnFullBucketDoesNotBankPhantomRefills() {
        var rl = limiter(2, 1, SEC);
        rl.tryAcquire("a");
        advance(100 * SEC);
        rl.tryAcquire("a");
        rl.tryAcquire("a");

        assertFalse(rl.tryAcquire("a").allowed());
    }

    @Test
    void resetAtIsWhenBucketBecomesFull() {
        var rl = limiter(3, 1, SEC);

        RateLimitResult r = rl.tryAcquire("a");
        assertEquals(START.plusSeconds(1), r.resetAt());

        r = rl.tryAcquire("a");
        assertEquals(START.plusSeconds(2), r.resetAt());
    }

    @Test
    void hugeConfigValuesDoNotOverflow() {
        var rl = limiter(Integer.MAX_VALUE, 1, Long.MAX_VALUE / 2);
        assertDoesNotThrow(() -> rl.tryAcquire("x"));
    }

    @Test
    void keysAreIndependent() {
        var rl = limiter(1, 1, SEC);
        assertTrue(rl.tryAcquire("a").allowed());
        assertFalse(rl.tryAcquire("a").allowed());
        assertTrue(rl.tryAcquire("b").allowed());
    }

    @Test
    void nullKeyThrows() {
        var rl = limiter(1, 1, SEC);
        assertThrows(NullPointerException.class, () -> rl.tryAcquire(null));
    }

    @Test
    void invalidConfigThrows() {
        assertThrows(IllegalArgumentException.class, () -> limiter(0, 1, SEC));
        assertThrows(IllegalArgumentException.class, () -> limiter(1, 0, SEC));
        assertThrows(IllegalArgumentException.class, () -> limiter(1, 1, 0));
    }

    @Test
    void tickerGoingBackwardsThrows() {
        var rl = limiter(1, 1, SEC);
        rl.tryAcquire("a");
        advance(-1);
        assertThrows(IllegalStateException.class, () -> rl.tryAcquire("a"));
    }

    @Test
    void concurrentCallsNeverExceedBucketSize() throws Exception {
        int bucket = 100, threads = 16, totalCalls = 2_000;
        var rl = limiter(bucket, 1, 1_000 * SEC);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < totalCalls; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                if (rl.tryAcquire("shared").allowed()) allowed.incrementAndGet();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(bucket, allowed.get());
    }

    @Test
    void realTickerUnderContentionNeverThrows() throws Exception {
        var rl = new TokenBucketRateLimiter<String>(
                new TokenBucketConfig(1_000, 1, SEC), new InMemoryStorage<>(),
                Clock.systemUTC(), System::nanoTime);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) {
            futures.add(pool.submit(() -> rl.tryAcquire("shared")));
        }
        for (Future<?> f : futures) assertDoesNotThrow(() -> f.get(10, TimeUnit.SECONDS));
        pool.shutdown();
    }
}