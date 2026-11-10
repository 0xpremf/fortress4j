import com.github.fortress4j.algorithm.fixedwindow.FixedWindowRateLimiter;
import com.github.fortress4j.config.FixedWindowConfig;
import com.github.fortress4j.models.RateLimitResult;
import com.github.fortress4j.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FixedWindowRateLimiterConcurrencyTest {

    @Test
    void shouldAllowRequestsUntilLimitAndCalculateRemaining() {
        int limit = 3;
        Clock clock = Clock.fixed(
                Instant.parse("2026-01-01T00:00:00Z"),
                ZoneOffset.UTC
        );

        FixedWindowConfig config = new FixedWindowConfig(limit, Duration.ofSeconds(10));
        FixedWindowRateLimiter<String> limiter = new FixedWindowRateLimiter<>(
                config,
                new InMemoryStorage<>(),
                clock
        );

        RateLimitResult r1 = limiter.tryAcquire("user");
        assertTrue(r1.allowed());
        assertEquals(2, r1.remainingRequests());
        assertEquals(0L, r1.retryAfter());
        assertNotNull(r1.resetAt());

        RateLimitResult r2 = limiter.tryAcquire("user");
        assertTrue(r2.allowed());
        assertEquals(1, r2.remainingRequests());

        RateLimitResult r3 = limiter.tryAcquire("user");
        assertTrue(r3.allowed());
        assertEquals(0, r3.remainingRequests());

        RateLimitResult denied = limiter.tryAcquire("user");
        assertFalse(denied.allowed());
        assertEquals(0, denied.remainingRequests());
        assertTrue(denied.retryAfter() > 0);
        assertEquals(10_000L, denied.retryAfter());
        assertEquals(Instant.parse("2026-01-01T00:00:10Z"), denied.resetAt());
    }

    @Test
    void shouldAllowExactlyLimitRequestsConcurrently() throws Exception {
        int limit = 100;
        int threads = 500;

        Clock clock = Clock.fixed(
                Instant.parse("2026-01-01T00:00:00Z"),
                ZoneOffset.UTC
        );

        FixedWindowConfig config = new FixedWindowConfig(
                limit,
                Duration.ofSeconds(10)
        );

        FixedWindowRateLimiter<String> limiter =
                new FixedWindowRateLimiter<>(
                        config,
                        new InMemoryStorage<>(),
                        clock
                );

        ExecutorService executor = Executors.newFixedThreadPool(32);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(threads);

        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    RateLimitResult result = limiter.tryAcquire("user");
                    if (result.allowed()) {
                        allowed.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    finish.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(finish.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        if (!errors.isEmpty()) {
            errors.forEach(Throwable::printStackTrace);
            fail("Worker threads threw exceptions.");
        }

        assertEquals(limit, allowed.get());
        assertEquals(threads - limit, rejected.get());
    }
}

