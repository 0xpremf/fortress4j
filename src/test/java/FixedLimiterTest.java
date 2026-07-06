import com.github.fortress4j.algorithm.fixedwindow.FixedWindowRateLimiter;
import com.github.fortress4j.config.FixedWindowConfig;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertTrue;

public class FixedLimiterTest {

    @Test
    public void shouldAllowExactlyLimitRequestsUnderConcurrency() throws InterruptedException {

        int limit = 100;
        int totalRequests = 1000;
        int poolSize = 100;

        FixedWindowRateLimiter rateLimiter =
                new FixedWindowRateLimiter(
                        new FixedWindowConfig(limit, Duration.ofSeconds(10)));

        ExecutorService executor = Executors.newFixedThreadPool(poolSize);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(totalRequests);

        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < totalRequests; i++) {

            executor.submit(() -> {
                try {

                    startLatch.await();

                    if (rateLimiter.tryAcquire("user123")) {
                        allowed.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // Fire all waiting threads
        startLatch.countDown();

        // Wait for every request to finish
        finishLatch.await();

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Verify
        assertEquals(limit, allowed.get());
        assertEquals(totalRequests - limit, rejected.get());
    }



}
