import com.github.fortress4j.algorithm.TokenBucket.TokenBucketRateLimiter;
import com.github.fortress4j.config.TokenBucketConfig;
import org.testng.annotations.Test;

public class TokenBucketRateLimiterTest {

    @Test
    public void shouldAllowRequestUntilLimit(){
        TokenBucketConfig config = new TokenBucketConfig();

        TokenBucketRateLimiter rateLimiter = new TokenBucketRateLimiter<>();
    }

}
