-- Token bucket rate limiter.
--
-- The whole point of putting this in Lua is atomicity: a token bucket is a
-- read-compute-write, and Redis runs a script as a single indivisible operation.
-- Done as separate HMGET / HMSET calls from Java, two concurrent requests can both
-- read "1 token left" and both proceed.
--
-- KEYS[1] = bucket key
-- ARGV[1] = capacity        (max tokens the bucket holds)
-- ARGV[2] = refill_rate     (tokens per second)
-- ARGV[3] = now_millis      (passed in, so every instance shares the caller's clock
--                            rather than each Redis replica using its own)
-- ARGV[4] = requested       (tokens to consume, normally 1)
--
-- returns { allowed(0|1), tokens_remaining, retry_after_millis }

local capacity    = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now         = tonumber(ARGV[3])
local requested   = tonumber(ARGV[4])

if refill_rate <= 0 then
  return { 0, 0, 1000 }
end

local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'last_refill')
local tokens = tonumber(bucket[1])
local last   = tonumber(bucket[2])

if tokens == nil or last == nil then
  -- first sighting of this key: start full, so a new caller is not punished
  tokens = capacity
  last   = now
end

-- refill proportionally to elapsed time, clamped at capacity.
-- max(0, ...) guards against a caller clock that went backwards.
local elapsed = math.max(0, now - last) / 1000.0
tokens = math.min(capacity, tokens + elapsed * refill_rate)

local allowed     = 0
local retry_after = 0

if tokens >= requested then
  tokens  = tokens - requested
  allowed = 1
else
  -- how long until enough tokens have accrued for this request
  retry_after = math.ceil(((requested - tokens) / refill_rate) * 1000)
end

redis.call('HMSET', KEYS[1], 'tokens', tokens, 'last_refill', now)

-- Expire idle buckets, or memory grows once per distinct client forever. The TTL is
-- twice the time to refill from empty to full, so an expiring bucket is always one
-- that would have refilled to capacity anyway - expiry can never grant extra budget.
redis.call('PEXPIRE', KEYS[1], math.ceil((capacity / refill_rate) * 2000))

return { allowed, math.floor(tokens), retry_after }
