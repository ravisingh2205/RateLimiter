-- Token bucket. Atomic refill + take in one round trip.
-- KEYS[1] = bucket key (HASH: tokens, ts)
-- ARGV[1] = capacity, ARGV[2] = refill tokens per second, ARGV[3] = cost
-- Returns { allowed(0/1), remaining, retryAfterMs, resetMs }
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local cap = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])

local d = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(d[1])
local ts = tonumber(d[2])
if tokens == nil then
  tokens = cap
  ts = now
end

local elapsed = math.max(0, now - ts)
tokens = math.min(cap, tokens + (elapsed / 1000.0) * rate)

local allowed = 0
local retry = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
else
  retry = math.ceil((cost - tokens) / rate * 1000)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
local ttl = math.ceil(cap / rate * 1000) * 2 + 1000
redis.call('PEXPIRE', KEYS[1], ttl)

local reset = math.ceil((cap - tokens) / rate * 1000)
return { allowed, math.floor(tokens), retry, reset }
