import com.github.fortress4j.algorithm.slindingWindow.SlidingWindowRateLimiter;
import com.github.fortress4j.config.SlidingWindowConfig;
import com.github.fortress4j.states.SlidingWindowState;
import com.github.fortress4j.storage.InMemoryStorage;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;


public class SlidingWindowTest {



    @Test
    public void shouldAllowRequestsUptoLimit(){

        int limit = 5;


        InMemoryStorage<String, SlidingWindowState> inMemoryStorage = new InMemoryStorage<>();
        SlidingWindowRateLimiter<String> limiter = new SlidingWindowRateLimiter<>(new SlidingWindowConfig(limit, Duration.ofSeconds(60L)), inMemoryStorage);

        for (int i = 0; i < limit; i++) {
            assertTrue(limiter.tryAcquire("user1"));
        }

        assertFalse(limiter.tryAcquire("user1"));

    }




}
