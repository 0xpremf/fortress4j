import com.github.fortress4j.algorithm.fixedwindow.FixedWindowRateLimiter;
import com.github.fortress4j.algorithm.slindingWindow.SlidingWindowRateLimiter;
import com.github.fortress4j.config.FixedWindowConfig;
import com.github.fortress4j.config.SlidingWindowConfig;
import com.github.fortress4j.models.RateLimitResult;
import com.github.fortress4j.states.SlidingWindowState;
import com.github.fortress4j.storage.InMemoryStorage;
import org.testng.annotations.Test;

import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.testng.AssertJUnit.*;




public class SlidingWindowRateLimiterTest {

        @Test
        public void shouldAllowRequestsUntilLimit() {

            Clock clock = Clock.fixed(
                    Instant.parse("2026-01-01T00:00:00Z"),
                    ZoneOffset.UTC
            );

            SlidingWindowConfig config = new SlidingWindowConfig(
                    5,
                    Duration.ofSeconds(10)
            );

            SlidingWindowRateLimiter<String> limiter =
                    new SlidingWindowRateLimiter<>(
                            config,new InMemoryStorage<>(),
                            clock
                    );

            for (int i = 1; i <= 5; i++) {

                RateLimitResult result = limiter.tryAcquire("user1");

                assertTrue(result.allowed());
                assertEquals(5 - i, result.remainingRequests());
            }

            RateLimitResult rejected = limiter.tryAcquire("user1");

            assertFalse(rejected.allowed());
            assertEquals(0, rejected.remainingRequests());
            assertTrue(rejected.retryAfter().toMillis() > 0);
        }

        @Test
        void shouldHandleDifferentUsers(){
            int limit=2;
            SlidingWindowConfig config = new SlidingWindowConfig(limit,
                    Duration.ofSeconds(10));



            SlidingWindowRateLimiter<String> limiter = new SlidingWindowRateLimiter<>(config,new InMemoryStorage<>());
            assertTrue(limiter.tryAcquire("alice").allowed());
            assertTrue(limiter.tryAcquire("alice").allowed());

            // Bob
            assertTrue(limiter.tryAcquire("bob").allowed());

            // Alice exceeded
            assertFalse(limiter.tryAcquire("alice").allowed());

            // Bob still has one request left
            assertTrue(limiter.tryAcquire("bob").allowed());

            // Bob exceeded
            assertFalse(limiter.tryAcquire("bob").allowed());

        }

     @Test
    void shouldAllowExactlyLimitRequestsConcurrently() throws Exception {

        int limit = 100;
        int threads = 500;

        Clock clock = Clock.fixed(
                Instant.parse("2026-01-01T00:00:00Z"),
                ZoneOffset.UTC
        );

        SlidingWindowConfig config = new SlidingWindowConfig(
                limit,
                Duration.ofSeconds(10)
        );

        SlidingWindowRateLimiter<String> limiter =
                new SlidingWindowRateLimiter<>(
                        config,
                        new InMemoryStorage<>(),
                        clock
                );

        ExecutorService executor = Executors.newFixedThreadPool(100);

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




        executor.shutdown();

        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));


         assertEquals(
                 threads,
                 allowed.get() + rejected.get()
         );
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

    private static final class MutableClock extends Clock {

        private Instant now;
        private final ZoneId zone;

        private MutableClock(Instant now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
    @Test
    void remainingRequestsMatchesStrictLimitAtFractionalPreviousWindow() {

        MutableClock clock = new MutableClock(
                Instant.parse("2026-01-01T00:00:00Z"),
                ZoneOffset.UTC
        );

        SlidingWindowConfig config = new SlidingWindowConfig(
                5,
                Duration.ofSeconds(10)
        );

        SlidingWindowRateLimiter<String> limiter =
                new SlidingWindowRateLimiter<>(
                        config,
                        new InMemoryStorage<>(),
                        clock
                );

        // Fill the first window: currentWindowCount = 5
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("user").allowed());
        }

        // Move to the middle of the next window:
        // prevWindowCount = 5, weight = 0.5, estimate = 2.5
        clock.advance(Duration.ofSeconds(15));

        RateLimitResult first = limiter.tryAcquire("user");
        assertTrue(first.allowed());
        // 2.5 + 1 = 3.5; floor(5 - 3.5) = 1
        assertEquals(1L, first.remainingRequests());

        RateLimitResult second = limiter.tryAcquire("user");
        assertTrue(second.allowed());
        // 3.5 + 1 = 4.5; floor(5 - 4.5) = 0
        assertEquals(0L, second.remainingRequests());

        RateLimitResult third = limiter.tryAcquire("user");
        assertFalse(third.allowed());
        // 4.5 + 1 = 5.5 > 5, so rejected
        assertEquals(0L, third.remainingRequests());
    }



}




