package com.github.fortress4j.redis;

import com.github.fortress4j.config.TokenBucketConfig;
import com.github.fortress4j.models.RateLimitResult;
import com.redis.testcontainers.RedisContainer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class RedisTokenBucketRateLimiterTest {

    private static final long INTERVAL_MS = 1_000L;
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Container
    static final RedisContainer REDIS = new RedisContainer("redis:8.8.4-alpine");

    static RedisClient client;
    static StatefulRedisConnection<String, String> connection;

    // Fake server clock handed to the Lua script as ARGV[4].
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

    private RedisTokenBucketRateLimiter<String> limiter(long bucketSize, long refillAmount, long intervalMs) {
        var config = new TokenBucketConfig(
                bucketSize, refillAmount, Duration.ofMillis(intervalMs).toNanos());
        return new RedisTokenBucketRateLimiter<>(
                config, connection, "test:", String::valueOf,
                Clock.fixed(START, ZoneOffset.UTC), timeMs::get);
    }

    private void advance(long ms) {
        timeMs.addAndGet(ms);
    }

    private static int runConcurrently(RedisTokenBucketRateLimiter<String> limiter,
                                       int threads, int calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < calls; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                if (limiter.tryAcquire("shared").allowed()) {
                    allowed.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        return allowed.get();
    }

    @Test
    void allowsExactlyBucketSizeThenDenies() {
        var rl = limiter(3, 1, INTERVAL_MS);

        assertEquals(2, rl.tryAcquire("a").remainingRequests());
        assertEquals(1, rl.tryAcquire("a").remainingRequests());
        assertEquals(0, rl.tryAcquire("a").remainingRequests());

        RateLimitResult denied = rl.tryAcquire("a");
        assertFalse(denied.allowed());
        assertEquals(0, denied.remainingRequests());
    }

    @Test
    void refillsTokensAfterInterval() {
        var rl = limiter(2, 1, INTERVAL_MS);
        assertTrue(rl.tryAcquire("a").allowed());
        assertTrue(rl.tryAcquire("a").allowed());
        assertFalse(rl.tryAcquire("a").allowed());

        advance(INTERVAL_MS);
        RateLimitResult refilled = rl.tryAcquire("a");
        assertTrue(refilled.allowed());
        assertEquals(0, refilled.remainingRequests());
        assertFalse(rl.tryAcquire("a").allowed());

        advance(INTERVAL_MS);
        assertTrue(rl.tryAcquire("a").allowed());
    }

    @Test
    void aRefillLargerThanTheBucketJustFillsIt() {
        var rl = limiter(2, 10, INTERVAL_MS);
        assertTrue(rl.tryAcquire("a").allowed());
        assertTrue(rl.tryAcquire("a").allowed());
        assertFalse(rl.tryAcquire("a").allowed());

        advance(2 * INTERVAL_MS);
        RateLimitResult refilled = rl.tryAcquire("a");
        assertTrue(refilled.allowed());
        assertEquals(1, refilled.remainingRequests());   // capped at the bucket size
        assertTrue(rl.tryAcquire("a").allowed());
        assertFalse(rl.tryAcquire("a").allowed());
    }

    @Test
    void keysAreIndependent() {
        var rl = limiter(1, 1, INTERVAL_MS);
        assertTrue(rl.tryAcquire("a").allowed());
        assertFalse(rl.tryAcquire("a").allowed());
        assertTrue(rl.tryAcquire("b").allowed());
    }

    @Test
    void storesTokensAndLastRefillInAHash() {
        var rl = limiter(2, 1, INTERVAL_MS);
        rl.tryAcquire("a");

        assertEquals("hash", connection.sync().type("test:a"));
        Map<String, String> state = connection.sync().hgetall("test:a");
        assertEquals(Set.of("tokens", "last_refill"), state.keySet());
        assertEquals("1", state.get("tokens"));
        assertEquals("1000000", state.get("last_refill"));
    }

    @Test
    void deniedRequestsDoNotWriteOrRefreshTheTtl() {
        var rl = limiter(1, 1, INTERVAL_MS);
        rl.tryAcquire("a");

        Map<String, String> before = connection.sync().hgetall("test:a");
        long pttlBefore = connection.sync().pttl("test:a");

        advance(400);
        assertFalse(rl.tryAcquire("a").allowed());

        assertEquals(before, connection.sync().hgetall("test:a"));
        assertTrue(connection.sync().pttl("test:a") <= pttlBefore,
                "denials must not refresh the TTL");
    }

    @Test
    void ttlHasAOneSecondFloor() {
        var rl = limiter(1, 1, 1);              // 1 ms interval → script floor applies
        rl.tryAcquire("a");

        long pttl = connection.sync().pttl("test:a");
        assertTrue(pttl > 0 && pttl <= 1_000, "pttl was " + pttl);
    }

    @Test
    void ttlCoversTimeUntilFullPlusTwoIntervals() {
        var rl = limiter(5, 1, INTERVAL_MS);    // 1 s to full, then 2 intervals of slack
        rl.tryAcquire("a");

        long pttl = connection.sync().pttl("test:a");
        assertTrue(pttl > 2 * INTERVAL_MS && pttl <= 3 * INTERVAL_MS, "pttl was " + pttl);
    }

    @Test
    void idleKeyExpiresAndExpiryLosesNothing() throws InterruptedException {
        var rl = limiter(1, 1, 1);              // TTL floor is one second
        assertTrue(rl.tryAcquire("a").allowed());

        Thread.sleep(1_100);                    // real time: the state key must go away

        assertEquals(0, connection.sync().exists("test:a").longValue());
        RateLimitResult fresh = rl.tryAcquire("a");
        assertTrue(fresh.allowed());
        assertEquals(0, fresh.remainingRequests());
    }

    @Test
    void usesTheConfiguredKeyPrefixAndMapper() {
        var config = new TokenBucketConfig(1, 1, Duration.ofSeconds(1).toNanos());
        var rl = new RedisTokenBucketRateLimiter<String>(
                config, connection, "custom:", k -> "id-" + k);

        rl.tryAcquire("a");

        assertEquals(1, connection.sync().exists("custom:id-a").longValue());
        assertEquals(0, connection.sync().exists("test:a").longValue());
    }

    @Test
    void longIdleResetsLastRefillToNow() {
        var rl = limiter(2, 1, INTERVAL_MS);
        rl.tryAcquire("a");
        rl.tryAcquire("a");                     // empty at t = 1_000_000

        advance(10 * INTERVAL_MS);              // idle far longer than the bucket needs
        assertTrue(rl.tryAcquire("a").allowed());

        // Full refill rebases the clock instead of storing the last boundary.
        assertEquals("1010000", connection.sync().hget("test:a", "last_refill"));
    }

    @Test
    void partialProgressIsStoredAsTheLastBoundary() {
        var rl = limiter(2, 1, INTERVAL_MS);
        rl.tryAcquire("a");
        rl.tryAcquire("a");                     // empty at t = 1_000_000

        advance(1_500);                         // one whole interval plus 500 ms
        assertTrue(rl.tryAcquire("a").allowed());

        // Only whole intervals are consumed; the extra 500 ms is preserved.
        assertEquals("1001000", connection.sync().hget("test:a", "last_refill"));

        advance(500);                           // exactly the next boundary
        assertTrue(rl.tryAcquire("a").allowed());
    }

    @Test
    void serverClockSteppingBackDoesNotThrowAndDoesNotRefill() {
        var rl = limiter(1, 1, INTERVAL_MS);
        assertTrue(rl.tryAcquire("a").allowed());

        advance(500);
        timeMs.addAndGet(-10_000);              // server clock jumps backwards

        RateLimitResult denied = assertDoesNotThrow(() -> rl.tryAcquire("a"));
        assertFalse(denied.allowed());
        assertEquals(INTERVAL_MS, denied.retryAfter());
    }

    @Test
    void usesTheRedisServerClockWhenThereIsNoOverride() {
        var config = new TokenBucketConfig(1, 1, Duration.ofSeconds(60).toNanos());
        var rl = new RedisTokenBucketRateLimiter<String>(config, connection);

        assertTrue(rl.tryAcquire("a").allowed());

        RateLimitResult denied = rl.tryAcquire("a");
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfter() > 0 && denied.retryAfter() <= 60_000,
                "retryAfter was " + denied.retryAfter());
    }

    @Test
    void twoLimiterInstancesShareOneBucket() {
        var first = limiter(2, 1, INTERVAL_MS);
        var second = limiter(2, 1, INTERVAL_MS);

        assertTrue(first.tryAcquire("a").allowed());
        assertTrue(second.tryAcquire("a").allowed());
        assertFalse(first.tryAcquire("a").allowed());
        assertFalse(second.tryAcquire("a").allowed());
    }

    @Test
    void rejectsNonPositiveValues() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketConfig(0, 1, 1_000_000));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketConfig(1, 0, 1_000_000));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketConfig(1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketConfig(1, 1, -1_000_000));
    }

    @Test
    void rejectsSubMillisecondIntervals() {
        var halfMilli = new TokenBucketConfig(3, 1, 500_000);        // 0.5 ms
        assertThrows(IllegalArgumentException.class,
                () -> new RedisTokenBucketRateLimiter<String>(halfMilli, connection));

        var oneAndAHalf = new TokenBucketConfig(3, 1, 1_500_000);    // 1.5 ms
        assertThrows(IllegalArgumentException.class,
                () -> new RedisTokenBucketRateLimiter<String>(oneAndAHalf, connection));
    }

    @Test
    void rejectsBucketsLargerThanIntegerMax() {
        // The invariant belongs to the config, so it fails at construction time.
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucketConfig(Integer.MAX_VALUE + 1L, 1, Duration.ofSeconds(1).toNanos()));
    }

    @Test
    void rejectsConfigsWhoseKeyTtlWouldBeAbsurd() {
        // ~68 years to refill: the key would outlive any realistic deployment.
        // NOTE: the third argument is NANOS, so 1 s == 1_000_000_000.
        var absurd = new TokenBucketConfig(Integer.MAX_VALUE, 1, Duration.ofSeconds(1).toNanos());
        assertThrows(IllegalArgumentException.class,
                () -> new RedisTokenBucketRateLimiter<String>(absurd, connection));
    }

    @Test
    void acceptsLargeButRealisticConfigs() {
        var large = new TokenBucketConfig(1_000_000, 1_000, Duration.ofSeconds(1).toNanos());
        var rl = new RedisTokenBucketRateLimiter<String>(
                large, connection, "test:", String::valueOf,
                Clock.fixed(START, ZoneOffset.UTC), timeMs::get);

        assertEquals(999_999, rl.tryAcquire("a").remainingRequests());
    }

    @Test
    void concurrentCallsNeverExceedBucketSize_fakeClock() throws Exception {
        var rl = limiter(100, 1, 1_000_000);    // refills far outside the test horizon
        assertEquals(100, runConcurrently(rl, 16, 2_000));
    }

    @Test
    void concurrentCallsNeverExceedBucketSize_realRedisClock() throws Exception {
        var config = new TokenBucketConfig(100, 1, Duration.ofHours(1).toNanos());
        var rl = new RedisTokenBucketRateLimiter<String>(
                config, connection, "test:", String::valueOf);

        assertEquals(100, runConcurrently(rl, 16, 2_000));
    }

    @Test
    void survivesScriptCacheFlush() {
        var rl = limiter(3, 1, INTERVAL_MS);
        assertTrue(rl.tryAcquire("a").allowed());

        connection.sync().scriptFlush();

        // The limiter must reload the script AND keep its state: 1 of 3 used.
        RateLimitResult afterReload = rl.tryAcquire("a");
        assertTrue(afterReload.allowed());
        assertEquals(1, afterReload.remainingRequests());
    }
}
