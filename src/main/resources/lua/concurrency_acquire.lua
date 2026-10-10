-- Concurrency limiter: a ZSET of leases scored by expiry time. Leases that are never
-- released (crashed node, dropped connection) expire on their own.
-- KEYS[1] = lease set key
-- ARGV[1] = max in-flight, ARGV[2] = lease TTL in ms, ARGV[3] = lease id
-- Returns { allowed(0/1), remaining, retryAfterMs, resetMs }
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local max = tonumber(ARGV[1])
local ttl = tonumber(ARGV[2])

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
local inflight = redis.call('ZCARD', KEYS[1])
if inflight >= max then
  local first = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
  local wait = 1000
  if first[2] then
    wait = math.max(1, tonumber(first[2]) - now)
  end
  return { 0, 0, wait, wait }
end

redis.call('ZADD', KEYS[1], now + ttl, ARGV[3])
redis.call('PEXPIRE', KEYS[1], ttl * 2)
return { 1, max - inflight - 1, 0, 0 }
