# Design Decisions & Trade-offs

This document explains why the rate limiter is built the way it is, what each choice costs, and what would come next.

## 1. Redis + Lua for atomic decisions

**Decision:** keep all bucket state in Redis and make every decision with one Lua script call.

**Why:** the limiter must be correct across many stateless application instances. A read-then-write from Java
(`HMGET` followed by `HSET`) would let two instances read the same token count and both allow a request.
Redis executes scripts atomically, so refill, check, deduct and persist happen as one indivisible step, without
distributed locks and with a single network round trip.

**Trade-off:** every request costs one Redis call, so Redis throughput is the ceiling (see the roadmap).

## 2. Redis `TIME` as the clock

**Decision:** the script reads the current time with `redis.call('TIME')` instead of receiving it from the application.

**Why:** refill depends on elapsed time. If each application node sent its own wall clock, clock skew between nodes
would refill buckets faster or slower depending on which node handled the request. Using the Redis clock gives the
whole fleet one time source. Since Redis 5, scripts replicate their effects rather than the script itself, so
calling `TIME` before writing is safe.

## 3. Token bucket

**Decision:** token bucket rather than fixed or sliding window.

**Why:** it allows controlled bursts (`capacity`) while enforcing a sustained rate (`refill-rate-per-second`), needs
only two fields per client, and the time until the next token can be computed exactly, which gives an accurate
`Retry-After`. The `RateLimiter` interface keeps other algorithms pluggable.

## 4. Defensive script

- Invalid arguments (non-positive values, `cost > capacity`) return a Redis error **before** any write, so bad
  configuration can never persist broken state. The application also validates the properties at startup.
- Missing or non-numeric hash fields reset the bucket instead of failing every request for that client.
- Remaining tokens are returned as a string because Redis truncates Lua numbers to integers in replies.

## 5. TTL = time to refill

Each write sets `PEXPIRE capacity / refill_rate`. Once that much time passes without traffic the bucket would be
full again, which is exactly the state of a brand new bucket, so deleting it changes nothing. This keeps memory
proportional to *active* clients. The TTL is in milliseconds and rounded up, so even very fast refill rates expire.

## 6. Explicit failure policy and latency budget

- `spring.data.redis.timeout: 250ms` caps how long a request waits for Redis. Lettuce's default is 60 s, which during
  an incident would hold every request thread for a minute.
- After a failure the configured policy applies: **fail-open** (allow, flag with `X-RateLimit-Degraded`) or
  **fail-closed** (`503`). The default is fail-closed. Choose fail-open for public APIs where availability matters most.
- Backend failures are logged on one line without a stack trace or client identifier, so an outage does not
  flood the logs or leak API keys.

## 7. HTTP contract

- `429 Too Many Requests` with `Retry-After` rounded **up** to whole seconds, so clients never retry too early.
- `X-RateLimit-Limit` and `X-RateLimit-Remaining` on every limited response.
- Error bodies follow RFC 9457 (`application/problem+json`).
- `/actuator/**` is excluded by default so health checks and metrics scraping never consume client quota.

## 8. Code structure

| Concern | Type | Pattern |
|---|---|---|
| Rate limiting algorithm | `RateLimiter` / `RedisTokenBucketRateLimiter` | Strategy |
| Metrics | `MeteredRateLimiter` | Decorator |
| Client identity | `ClientKeyResolver` / `ApiKeyOrIpClientKeyResolver` | Strategy |
| HTTP translation | `RateLimitingFilter` | Servlet filter |
| Configuration | `RateLimiterProperties` | Validated immutable record |

The filter knows HTTP, the limiter knows Redis, and neither knows about metrics.

## Roadmap

Ideas for taking this to internet scale, roughly in order of value:

1. **Redis Cluster / sharding**: keys are already per client, so they distribute naturally across shards. Hash tags
   would only be needed for multi-key scripts.
2. **Local pre-check for hot keys**: a short-lived in-memory deny cache for clients that are clearly over the limit,
   to shed load before it reaches Redis.
3. **Per-route and per-plan limits**: resolve capacity and refill rate from the route or the client's plan instead of
   a single global configuration.
4. **Circuit breaker around Redis**: stop calling Redis for a short window after repeated timeouts, so the failure
   policy applies immediately instead of after each 250 ms timeout.
5. **IETF `RateLimit` headers**: adopt the standardized `RateLimit` / `RateLimit-Policy` headers once the draft is final.
6. **Latency histograms**: add a timer for script latency, to set SLOs on the limiter itself.
