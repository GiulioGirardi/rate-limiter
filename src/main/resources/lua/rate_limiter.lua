-- Token Bucket rate limiter script
-- KEYS[1] - rate limiter key (per client)
-- ARGV[1] - capacity (max tokens)
-- ARGV[2] - refill_rate (tokens per second)
-- ARGV[3] - cost (tokens per request)
-- ARGV[4] - now (current time in milliseconds)
--
-- Hash structure:
--  tokens (float)
--  last_refill (millis)

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])
local now = tonumber(ARGV[4])

local ttl = 0
if refill_rate > 0 then
  ttl = math.floor(capacity / refill_rate)
end

local data = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = data[1]
local last_refill = data[2]

if tokens == false or tokens == nil or last_refill == false or last_refill == nil then
  -- Initialize new bucket as full to allow bursts immediately
  tokens = capacity
  last_refill = now
else
  tokens = tonumber(tokens)
  last_refill = tonumber(last_refill)

  local elapsed = now - last_refill
  if elapsed < 0 then
    elapsed = 0
  end

  local refill = (elapsed * refill_rate) / 1000.0
  tokens = math.min(capacity, tokens + refill)
  last_refill = now
end

local allowed = 0
if tokens >= cost then
  allowed = 1
  tokens = tokens - cost
end

redis.call('HMSET', key, 'tokens', tokens, 'last_refill', last_refill)

if ttl > 0 then
  redis.call('EXPIRE', key, ttl)
end

return { allowed, tokens }


