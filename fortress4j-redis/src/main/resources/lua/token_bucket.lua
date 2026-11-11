-- KEYS[1] = bucket key (a hash with fields: tokens, last_refill)
-- ARGV[1] = bucket size (maximum capacity)
-- ARGV[2] = refill amount (tokens added per interval)
-- ARGV[3] = refill interval, ms
-- ARGV[4] = time override in ms, or -1 to use the Redis server clock (tests only)
-- Returns { allowed (1/0), remainingTokens, retryAfterMs, resetAfterMs }

local bucketSize     = tonumber(ARGV[1])
local refillAmount   = tonumber(ARGV[2])
local refillInterval = tonumber(ARGV[3])
local override       = tonumber(ARGV[4])

-- 1. Determine current timestamp in milliseconds
local now
if override ~= nil and override >= 0 then
    now = override
else
    local t = redis.call('TIME')
    now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end

-- 2. Load existing token bucket state
local data          = redis.call('HMGET', KEYS[1], 'tokens', 'last_refill')
local currentTokens = tonumber(data[1])
local lastRefill    = tonumber(data[2])

-- 3. If no state exists yet, initialize a full bucket
if currentTokens == nil or lastRefill == nil then
    currentTokens = bucketSize
    lastRefill    = now
end

-- Server clock stepped back: protect against negative elapsed time
if now < lastRefill then
    now = lastRefill
end

-- 4. Calculate token refill
local elapsed = now - lastRefill
local intervalElapsed = math.floor(elapsed / refillInterval)

local tokenNeeded = bucketSize - currentTokens
local intervalsNeeded = math.ceil(tokenNeeded / refillAmount)

local updatedLastRefill = lastRefill
local refilledTokens    = currentTokens

if tokenNeeded <= 0 or intervalElapsed >= intervalsNeeded then
    refilledTokens    = bucketSize
    updatedLastRefill = now
else
    local tokensToAdd = intervalElapsed * refillAmount
    updatedLastRefill = lastRefill + (intervalElapsed * refillInterval)
    refilledTokens    = currentTokens + tokensToAdd
end

-- 5. Decide
local allowed = 0
local remainingTokens = 0
local retryAfter = 0

if refilledTokens > 0 then
    allowed = 1
    remainingTokens = refilledTokens - 1
    retryAfter = 0
else
    allowed = 0
    remainingTokens = 0
    local elapsedSinceBoundary = now - updatedLastRefill
    local timeUntilNextRefill = refillInterval - (elapsedSinceBoundary % refillInterval)
    retryAfter = math.max(1, timeUntilNextRefill)
end

-- 6. Estimate when the bucket will be completely full again
local missingTokens = bucketSize - remainingTokens
local intervalsUntilFull = math.ceil(missingTokens / refillAmount)
local totalRefillMs = intervalsUntilFull * refillInterval
local elapsedSinceBoundary = now - updatedLastRefill
local resetAfter = math.max(0, totalRefillMs - elapsedSinceBoundary)

-- 7. Persist state and TTL only when a token is consumed
if allowed == 1 then
    redis.call('HSET', KEYS[1], 'tokens', remainingTokens, 'last_refill', updatedLastRefill)
    local ttl = math.max(1000, math.ceil(resetAfter + refillInterval * 2))
    redis.call('PEXPIRE', KEYS[1], ttl)
end

return { allowed, remainingTokens, retryAfter, resetAfter }

