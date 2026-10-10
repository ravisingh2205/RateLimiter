-- Fixed window counter. Denied requests do not consume quota.
-- KEYS[1] = counter key
-- ARGV[1] = limit, ARGV[2] = window in milliseconds
-- Returns { allowed(0/1), remaining, retryAfterMs, resetMs }
local limit = tonumber(ARGV[1])
local win = tonumber(ARGV[2])

local c = tonumber(redis.call('GET', KEYS[1]) or '0')
if c >= limit then
  local ttl = redis.call('PTTL', KEYS[1])
  if ttl < 0 then
    redis.call('PEXPIRE', KEYS[1], win)
    ttl = win
  end
  return { 0, 0, ttl, ttl }
end

c = redis.call('INCR', KEYS[1])
if c == 1 then
  redis.call('PEXPIRE', KEYS[1], win)
end
local ttl = redis.call('PTTL', KEYS[1])
if ttl < 0 then
  redis.call('PEXPIRE', KEYS[1], win)
  ttl = win
end
return { 1, limit - c, 0, ttl }
