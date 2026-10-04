-- Token bucket rate limiter, executed atomically by Redis.
--
-- KEYS[1] - bucket key (one per client)
-- ARGV[1] - capacity (max tokens, i.e. burst size)
-- ARGV[2] - refill rate (tokens per second)
-- ARGV[3] - cost (tokens consumed by this request)
--
-- Bucket hash fields:
--   tokens      - tokens currently available (float)
--   last_refill - last refill timestamp in milliseconds (Redis server clock)
--
-- Returns { allowed (1|0), remaining_tokens (string), retry_after_ms }.
-- remaining_tokens is a string because Redis truncates Lua numbers to integers in replies.

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])

if not capacity or not refill_rate or not cost
    or capacity <= 0 or refill_rate <= 0 or cost <= 0 or cost > capacity then
  return redis.error_reply('ERR invalid token bucket arguments')
end

-- Use the Redis clock so every application node shares the same time source.
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

-- tonumber() yields nil for missing or corrupted fields, which resets the bucket.
local data = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(data[1])
local last_refill = tonumber(data[2])

if tokens == nil or last_refill == nil then
  -- New bucket starts full so clients can burst immediately.
  tokens = capacity
else
  local elapsed = math.max(0, now - last_refill)
  tokens = math.min(capacity, tokens + elapsed * refill_rate / 1000)
end

local allowed = 0
local retry_after_ms = 0
if tokens >= cost then
  allowed = 1
  tokens = tokens - cost
else
  retry_after_ms = math.ceil((cost - tokens) * 1000 / refill_rate)
end

redis.call('HSET', key, 'tokens', tokens, 'last_refill', now)

-- Expire once an idle bucket would be full again: at that point it is
-- indistinguishable from a brand new one, so keeping it only wastes memory.
redis.call('PEXPIRE', key, math.ceil(capacity * 1000 / refill_rate))

return { allowed, tostring(tokens), retry_after_ms }
