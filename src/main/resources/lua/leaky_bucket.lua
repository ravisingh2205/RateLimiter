-- Leaky bucket (as a meter): water level rises by 1 per request and drains at a constant rate.
-- A request is rejected when it would overflow the bucket. Output towards the downstream
-- therefore never exceeds the leak rate (sustained) and the queue depth never exceeds capacity.
-- KEYS[1] = bucket key (HASH: level, ts)
-- ARGV[1] = capacity, ARGV[2] = leak rate per second
-- Returns { allowed(0/1), remaining, retryAfterMs, resetMs }
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local cap = tonumber(ARGV[1])
local leak = tonumber(ARGV[2])

local d = redis.call('HMGET', KEYS[1], 'level', 'ts')
local level = tonumber(d[1]) or 0
local ts = tonumber(d[2]) or now

local elapsed = math.max(0, now - ts)
level = math.max(0, level - (elapsed / 1000.0) * leak)

local allowed = 0
local retry = 0
if level + 1 <= cap then
  level = level + 1
  allowed = 1
else
  retry = math.ceil((level + 1 - cap) / leak * 1000)
end

redis.call('HSET', KEYS[1], 'level', tostring(level), 'ts', tostring(now))
redis.call('PEXPIRE', KEYS[1], math.ceil(cap / leak * 1000) * 2 + 1000)

local reset = math.ceil(level / leak * 1000)
return { allowed, math.floor(cap - level), retry, reset }
