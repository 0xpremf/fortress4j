-- KEYS[1] = state key (hash: idx, cur, prev)
-- ARGV[1] = limit
-- ARGV[2] = window length, ms
-- ARGV[3] = key TTL, ms
-- ARGV[4] = time override in ms, or -1 to use the Redis server clock (tests only)
-- Returns { allowed (1/0), remaining, retryAfterMs, resetAfterMs }

local limit    = tonumber(ARGV[1])
local window   = tonumber(ARGV[2])
local ttl      = tonumber(ARGV[3])
local override = tonumber(ARGV[4])

-- 1. Time (server clock unless a test overrides it).
local now
if override ~= nil and override >= 0 then
  now = override
else
  local t = redis.call('TIME')
  now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end

-- 2. Which window are we in? Windows are aligned to multiples of `window`.
local idx = math.floor(now / window)

local data = redis.call('HMGET', KEYS[1], 'idx', 'cur', 'prev')
local sidx, cur, prev = tonumber(data[1]), tonumber(data[2]), tonumber(data[3])

-- 3. Bring the stored state up to the current window.
if sidx == nil or cur == nil or prev == nil then
  cur, prev = 0, 0                          -- never seen: empty
elseif idx > sidx then
  if idx == sidx + 1 then prev = cur else prev = 0 end   -- gap of 2+ windows: old data is irrelevant
  cur = 0
elseif idx < sidx then
  idx = sidx                                -- server clock stepped back: stay in the stored window
end

local elapsed = now - idx * window
if elapsed < 0 then elapsed = 0 end

-- 4. Decide.
local weightedPrev = math.floor(prev * (window - elapsed) / window)
local allowed, retryAfter = 0, 0

if weightedPrev + cur < limit then
  cur = cur + 1
  allowed = 1
else
  -- Default: wait for the next window boundary.
  retryAfter = window - elapsed
  -- If room opens up earlier (prev's weight shrinking), find the first millisecond it does.
  local need = limit - cur
  if need >= 1 and prev > 0 then
    local e = math.floor(window - need * window / prev) + 1
    if e < window then
      retryAfter = math.max(1, e - elapsed)
    end
  end
end

-- 5. Report.
local used = math.floor(prev * (window - elapsed) / window) + cur
local remaining = limit - used
if remaining < 0 then remaining = 0 end

-- Time until everything counted has aged out.
local resetAfter = 0
if cur > 0 then
  resetAfter = (window - elapsed) + window
elseif prev > 0 then
  resetAfter = window - elapsed
end

-- 6. Only allowed requests write (denied ones can't extend the key's life).
if allowed == 1 then
  redis.call('HSET', KEYS[1], 'idx', idx, 'cur', cur, 'prev', prev)
  redis.call('PEXPIRE', KEYS[1], ttl)
end

return { allowed, remaining, retryAfter, resetAfter }