# Identified Errors and Bugs in Fortress4j

This document lists all identified compilation errors, runtime bugs, algorithmic flaws, and architectural issues across the `fortress4j` project.

---

## 1. Fatal Compilation & Type Mismatch Errors

### 1.1 Incompatible `retryAfter` Type in `RateLimitResult`
- **Location:** `fortress4j-core/src/main/java/com/github/fortress4j/models/RateLimitResult.java`
- **Issue:** `RateLimitResult` was modified so that `retryAfter` is a `long`, but several rate limiter implementations and tests still expected or passed a `java.time.Duration`:
  - `SlidingWindowRateLimiter.java` (line 141) passed `retryAfter.get()` (`Duration`), failing with `error: incompatible types: Duration cannot be converted to long`.
  - `TokenBucketRateLimiter.java` (line 163) passed `retryAfter` (`Duration`), failing with `error: incompatible types: Duration cannot be converted to long`.
  - `SlidingWindowRateLimiterTest.java` (line 54) called `rejected.retryAfter().toMillis()`, failing with `error: long cannot be dereferenced`.
  - `TokenBucketRateLimiterTest.java` (line 59) asserted against `Duration.ofSeconds(1)`.
  - `RedisFixedRateLimiterTest.java` (line 88) asserted against `Duration.ofSeconds(6)`.
- **Fix:** Standardize `RateLimitResult` to use `long retryAfter` (in milliseconds), update all rate limiters to return `retryAfter` in milliseconds (`0` when allowed, time until reset/refill in ms when denied), and update tests accordingly.

### 1.2 Incompatible `windowSize` Type in `FixedWindowConfig`
- **Location:** `fortress4j-core/src/main/java/com/github/fortress4j/config/FixedWindowConfig.java`
- **Issue:** `FixedWindowConfig` changed `windowSize` from `Duration` to `long`, breaking callers expecting `Duration`:
  - `FixedLimiterTest.java` (line 28) passed `Duration.ofSeconds(10)`.
  - `RedisFixedRateLimiterTest.java` (line 58) passed `Duration.ofMillis(windowMs)`.
  - `SlidingWindowConfig` uses `Duration windowSize`, creating an API inconsistency.
- **Fix:** Maintain `Duration windowSize` as the canonical field while providing an overloaded constructor accepting `long windowSizeMillis` for backwards compatibility.

### 1.3 Java 25 Preview Feature Compilation Failure in `fortress4j-redis`
- **Location:** `fortress4j-redis/src/main/java/com/github/fortress4j/Main.java`
- **Issue:** Uses `IO.println` and instance/unnamed `static void main()`, which are preview features in Java 25. The root `pom.xml` does not enable `--enable-preview`, causing compilation failure.
- **Fix:** Replace with standard `public static void main(String[] args)` and `System.out.println`.

---

## 2. Redis & Lua Runtime Errors

### 2.1 Invalid Redis Command `'MEHMET'` in Lua Script
- **Location:** `fortress4j-redis/src/main/java/com/github/fortress4j/lua/fixed_rate_limit.lua` (line 23)
- **Issue:**
  ```lua
  local data = redis.call('MEHMET', KEYS[1], 'count', 'start')
  ```
  `'MEHMET'` is not a valid Redis command. Running this script will immediately fail at runtime with Redis error: `ERR unknown command 'MEHMET'`.
- **Fix:** Change `'MEHMET'` to the correct command `'HMGET'`.

### 2.2 Missing Lua Script on Classpath & Misplaced Directory
- **Location:** `fortress4j-redis/src/main/java/com/github/fortress4j/redis/RedisFixedWindowRateLimiter.java` (line 19)
- **Issue:**
  ```java
  private static final LuaScript SCRIPT = LuaScript.fromResource("/lua/fixed_window.lua");
  ```
  The script file was placed in `src/main/java/com/github/fortress4j/lua/fixed_rate_limit.lua`. By default, Maven does not copy `.lua` files from `src/main/java` to the classpath root `/lua/`, and the file name was `fixed_rate_limit.lua` instead of `fixed_window.lua`. This causes `LuaScript.fromResource` to fail with `IllegalStateException: Missing script: /lua/fixed_window.lua`.
- **Fix:** Move the script to `fortress4j-redis/src/main/resources/lua/fixed_window.lua`.

### 2.3 Broken Sub-Millisecond Nanosecond Check in `RedisFixedWindowRateLimiter`
- **Location:** `fortress4j-redis/src/main/java/com/github/fortress4j/redis/RedisFixedWindowRateLimiter.java` (lines 60-66)
- **Issue:**
  `config.windowSize() % NANOS_PER_MILLI != 0` checked nanoseconds against milliseconds when `windowSize` was raw `long`. If `windowSize` was passed as milliseconds (e.g. 10,000 ms), `10000 % 1_000_000 != 0`, throwing `IllegalArgumentException`.
- **Fix:** When `windowSize` is a `Duration`, check `config.windowSize().toNanos() % NANOS_PER_MILLI != 0` and pass `config.windowSize().toMillis()` to Redis.

---

## 3. Algorithmic & Logical Rate Limiting Bugs

### 3.1 Inverted / Negative `retryAfter` in `FixedWindowRateLimiter`
- **Location:** `fortress4j-core/src/main/java/com/github/fortress4j/algorithm/fixedwindow/FixedWindowRateLimiter.java` (line 62)
- **Issue:**
  ```java
  retryAfter.set((int) (now.toEpochMilli() - reset.toEpochMilli()));
  ```
  Since `reset` is in the future (`windowEnd > now`), `now - reset` yields a **negative value**.
- **Fix:** Change to `Math.max(0, reset.toEpochMilli() - now.toEpochMilli())`.

### 3.2 Unpopulated `remainingRequests` and `resetAt` on Success in `FixedWindowRateLimiter`
- **Location:** `fortress4j-core/src/main/java/com/github/fortress4j/algorithm/fixedwindow/FixedWindowRateLimiter.java` (lines 43-55, 69-74)
- **Issue:**
  - When creating a new state or resetting an expired window, `estimatedRequests` was never updated (remaining at `0`). This caused allowed requests in a fresh window to return `remainingRequests = 0`.
  - `resetAt` was only set when a request was rejected. On allowed requests, `resetAt` was `null`.
- **Fix:** Always set `estimatedRequests = config.limit() - state.getRequestCount()` and `resetAt = state.getWindowEnd()` on both allowed and rejected paths.

### 3.3 Ignoring Injected Clock in `FixedWindowState.resetState`
- **Location:** `fortress4j-core/src/main/java/com/github/fortress4j/states/FixedWindowState.java` (line 38)
- **Issue:**
  ```java
  public void resetState(Duration windowDuration) {
      requestCount = 0;
      windowEnd = Instant.now().plus(windowDuration);
  }
  ```
  Uses system wall clock `Instant.now()` instead of the limiter's injected `Clock`, which breaks deterministic testing and time travel.
- **Fix:** Pass the current instant into `resetState(Duration windowDuration, Instant now)`: `windowEnd = now.plus(windowDuration);`.

### 3.4 Local Variable Overwrite Corrupting `resetAt` in `TokenBucketRateLimiter`
- **Location:** `fortress4j-core/src/main/java/com/github/fortress4j/algorithm/tokenBucket/TokenBucketRateLimiter.java` (lines 89-92, 124)
- **Issue:**
  ```java
  if (tokenNeeded == 0 || intervalElapsed >= intervalsNeeded) {
      refillAmount = bucketSize; // <-- BUG: mutates local refillAmount
      updatedLastRefillTime = nowNanos;
  }
  ...
  long intervalsUntilFull = missingTokens / refillAmount;
  ```
  Mutating `refillAmount = bucketSize` caused `intervalsUntilFull` to divide by `bucketSize` instead of `config.refillAmount()`, severely underestimating the bucket reset time whenever requests arrived in a full bucket.
- **Fix:** Remove `refillAmount = bucketSize;` so `refillAmount` always reflects `config.refillAmount()`.

### 3.5 Bypassed Validation in `TokenBucketRateLimiter` Constructor
- **Location:** `fortress4j-core/src/main/java/com/github/fortress4j/algorithm/tokenBucket/TokenBucketRateLimiter.java` (lines 24-29)
- **Issue:** The 3-parameter constructor `TokenBucketRateLimiter(TokenBucketConfig, InMemoryStorage, LongSupplier)` duplicated assignment without calling `this(config, storage, Clock.systemUTC(), ticker)`, bypassing null checks and positive number validations.
- **Fix:** Chain constructor to `this(config, storage, Clock.systemUTC(), ticker)`.

### 3.6 Ticker Evaluation Race Condition Under Contention in `TokenBucketRateLimiter`
- **Location:** `fortress4j-core/src/main/java/com/github/fortress4j/algorithm/tokenBucket/TokenBucketRateLimiter.java`
- **Issue:** `ticker.getAsLong()` was called outside `storage.compute(key, ...)`. Under heavy multithreaded contention, a thread reading `nowNanos` earlier could execute its compute block after another thread that read a later `nowNanos`. This resulted in `nowNanos - lastRefillTime < 0` and triggered a false-positive `IllegalStateException: Ticker moved backwards`.
- **Fix:** Move `nowNanos = ticker.getAsLong()` inside `storage.compute`, guaranteeing evaluation order matches map lock acquisition order.

### 3.7 Inconsistent Window Boundary Alignment in `SlidingWindowRateLimiter`
- **Location:** `fortress4j-core/src/main/java/com/github/fortress4j/algorithm/slidingWindow/SlidingWindowRateLimiter.java` (lines 55, 80-86)
- **Issue:**
  - Initial creation set `currentWindowStartTime = now` (unaligned).
  - When `windowsPassed > 1`, line 81 forcibly aligned `currentWindowStartTime` to the epoch grid (`nowMillis - (nowMillis % windowMillis)`).
  This caused abrupt boundary jumps between cold starts and subsequent window resets.
- **Fix:** Consistently epoch-align window boundaries from initial creation:
  `long alignedWindowStart = nowMillis - (nowMillis % windowMillis);`
  and safeguard against negative elapsed time if clock steps backwards.

---

## 4. Test Code & Packaging Deficiencies

### 4.1 JUnit 4 vs JUnit 5 Inconsistencies
- **Location:** `fortress4j-core/src/test/java/SlidingWindowRateLimiterTest.java` (line 5)
- **Issue:** Imported `org.junit.Test` (JUnit 4) and `org.junit.Assert.assertFalse` along with JUnit 5 assertions.
- **Fix:** Standardize completely on JUnit 5 (`org.junit.jupiter.api.*`).

### 4.2 Class Name / File Name Mismatch
- **Location:** `fortress4j-core/src/test/java/FixedLimiterTest.java` and `fortress4j-redis/src/test/java/com/github/fortress4j/redis/RedisFixedRateLimiterTest.java`
- **Issue:**
  - `FixedLimiterTest.java` declared `class FixedWindowRateLimiterConcurrencyTest`.
  - `RedisFixedRateLimiterTest.java` declared `class RedisFixedWindowRateLimiterTest`.
  When launching tests from IDEs (Eclipse, VS Code, IntelliJ), the test runner attempts to find the type matching the launch configuration / file name. Because no compiled type matched the expected name, the IDE threw:
  `"The input type of the launch configuration does not exist"`.
- **Fix:**
  - Renamed `FixedLimiterTest.java` to `FixedWindowRateLimiterConcurrencyTest.java`.
  - Created `RedisFixedWindowRateLimiterTest.java` declaring `public class RedisFixedWindowRateLimiterTest` and alias `RedisFixedRateLimiterTest extends RedisFixedWindowRateLimiterTest` to support both launch targets.

### 4.3 Missing SLF4J 2.0 Binding with Testcontainers
- **Location:** `fortress4j-redis/pom.xml` and `pom.xml`
- **Issue:** Testcontainers 1.20.4 transitively brings in `slf4j-api:1.7.36`. When Logback 1.3+ / 1.4+ / 1.5+ was added, SLF4J 1.7 attempted to find the legacy binder `org.slf4j.impl.StaticLoggerBinder` (which was removed in Logback 1.3+ in favor of SLF4J 2.0's `SLF4JServiceProvider`). This produced the warning:
  ```
  SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder".
  SLF4J: Defaulting to no-operation (NOP) logger implementation
  ```
- **Fix:**
  - Managed `org.slf4j:slf4j-api:2.0.16` and `ch.qos.logback:logback-classic:1.5.34` in root `pom.xml` under `<dependencyManagement>`.
  - Added `slf4j-api` and `logback-classic` with `<scope>test</scope>` in `fortress4j-redis/pom.xml`.
  - Configured `logback-test.xml` in `fortress4j-redis/src/test/resources` to suppress verbose Lettue/Netty debug output.
