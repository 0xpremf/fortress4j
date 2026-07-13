import com.github.fortress4j.algorithm.slindingWindow.SlidingWindowRateLimiter;
import com.github.fortress4j.config.SlidingWindowConfig;
import com.github.fortress4j.models.RateLimitResult;
import com.github.fortress4j.states.SlidingWindowState;
import com.github.fortress4j.storage.InMemoryStorage;
import org.testng.annotations.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ThreadPoolExecutor;

import static org.testng.AssertJUnit.*;


import static org.junit.jupiter.api.Assertions.*;

    class SlidingWindowRateLimiterTest {

        @Test
        void shouldAllowRequestsUntilLimit() {

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
    }




