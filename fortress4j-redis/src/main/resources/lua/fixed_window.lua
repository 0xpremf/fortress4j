-- KEYS[1] = counter key (a hash with fields: count, start)
-- ARGV[1] = limit (max requests per window)
-- ARGV[2] = window length, ms
-- ARGV[3] = key TTL, ms
-- ARGV[4] = time override in ms, or -1 to use the Redis server clock (tests only)
-- Returns { allowed (1/0), remaining, retryAfterMs, resetAfterMs }

local limit    = tonumber(ARGV[1])
local window   = tonumber(ARGV[2])
local ttl      = tonumber(ARGV[3])
local override = tonumber(ARGV[4])

-- 1. What time is it? Use the server's clock so all app instances agree
local now
if override ~= nil and override >= 0 then
    now = override
else
    local t = redis.call('TIME')
    now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end

-- 2. Load the current window.
local data  = redis.call('HMGET', KEYS[1], 'count', 'start')
local count = tonumber(data[1])
local start = tonumber(data[2])

-- 3. No window yet, or it has ended: start a fresh one.
if count == nil or start == nil or now - start >= window then
    count = 0
    start = now
end

-- Server clock stepped back: keep counting in the current window instead of failing
if now < start then now = start end

-- 4. Decide
local resetAfter = start + window - now
local allowed = 0
local retryAfter = resetAfter

if count < limit then
    count = count + 1
    allowed = 1
    retryAfter = 0
    -- 5. Only allowed requests change state, so only they write.
    redis.call('HSET', KEYS[1], 'count', count, 'start', start)
    redis.call('PEXPIRE', KEYS[1], ttl)
end

return { allowed, limit - count, retryAfter, resetAfter }

