import com.github.fortress4j.algorithm.fixedwindow.FixedWindowRateLimiter;
import com.github.fortress4j.config.FixedWindowConfig;
import com.github.fortress4j.models.RateLimitResult;
import com.github.fortress4j.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FixedWindowRateLimiterConcurrencyTest {

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

        System.out.println("Allowed : " + allowed.get());
        System.out.println("Rejected: " + rejected.get());
        System.out.println("Total   : " + (allowed.get() + rejected.get()));

        if (!errors.isEmpty()) {
            errors.forEach(Throwable::printStackTrace);
            fail("Worker threads threw exceptions.");
        }

        assertEquals(limit, allowed.get());
        assertEquals(threads - limit, rejected.get());
    }





}
