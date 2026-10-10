-- Sliding window counter (weighted blend of previous and current fixed window).
-- KEYS[1] = base key; MUST carry a {hash tag} so the derived keys share a slot in Redis Cluster.
-- ARGV[1] = limit, ARGV[2] = window in milliseconds
-- Returns { allowed(0/1), remaining, retryAfterMs, resetMs }
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local limit = tonumber(ARGV[1])
local win = tonumber(ARGV[2])

local curStart = now - (now % win)
local curKey = KEYS[1] .. ':' .. curStart
local prevKey = KEYS[1] .. ':' .. (curStart - win)

local cur = tonumber(redis.call('GET', curKey) or '0')
local prev = tonumber(redis.call('GET', prevKey) or '0')
local elapsed = now - curStart
local weighted = prev * ((win - elapsed) / win) + cur

local untilNext = win - elapsed
if weighted + 1 > limit then
  -- upper bound: the window rolls over at 'untilNext'; sooner if prev contributes a lot
  return { 0, 0, untilNext, untilNext }
end

cur = redis.call('INCR', curKey)
redis.call('PEXPIRE', curKey, win * 2)
local remaining = math.floor(limit - (prev * ((win - elapsed) / win) + cur))
if remaining < 0 then remaining = 0 end
return { 1, remaining, 0, untilNext }
