## Distributed Rate Limiter (Token Bucket + Redis + Lua)

### Problem

This service implements a **distributed, per-client rate limiter** that must:

- work correctly across multiple Spring Boot instances,
- allow **bursts** up to a configured capacity,
- make **atomic** rate-limit decisions under high concurrency,
- and minimize latency for every decision.

All rate-limit state is centralized in **Redis**, and all decisions are made inside a single **Lua script** call to guarantee atomicity.

### Architecture

- **Spring Boot application**
  - Receives HTTP requests.
  - Extracts a client identifier from `X-API-Key` or falls back to client IP.
  - Delegates rate-limit decisions to a `RateLimiterService`.
  - A `OncePerRequestFilter` (`RateLimitingFilter`) enforces the decision:
    - **Allowed** → request proceeds.
    - **Rate-limited** → returns `HTTP 429 Too Many Requests`.
    - **Redis failure (fail-closed)** → returns `HTTP 503 Service Unavailable`.

- **Redis**
  - Single source of truth for bucket state.
  - One key per client, e.g. `rate_limiter:{clientId}`, stored as a hash:
    - `tokens` (float)
    - `last_refill` (timestamp in milliseconds)
  - Executes a **Lua script** that:
    - refills the bucket,
    - checks whether enough tokens exist,
    - deducts tokens if allowed,
    - sets a TTL to clean up inactive clients,
    - and returns `[allowedFlag, remainingTokens]`.

No local in-memory counters are used for the main logic; this keeps behavior **globally consistent** across all instances.

### Token Bucket Logic

Parameters (per request, provided by the application):

- `capacity`: maximum number of tokens in the bucket (burst size).
- `refill_rate`: tokens added per second.
- `cost`: tokens consumed per request (default = 1).
- `now`: current timestamp in milliseconds.

Redis hash per client:

- `tokens`: current tokens in the bucket.
- `last_refill`: last time the bucket was refilled (ms).

Refill formula:

- `elapsed = now - last_refill`
- `refill = elapsed * refill_rate / 1000`
- `tokens = min(capacity, tokens + refill)`

Decision:

- If `tokens >= cost`:
  - allow the request,
  - `tokens = tokens - cost`.
- Else:
  - reject the request as rate-limited.

### Redis + Lua Atomicity

All steps for a single request happen inside **one Lua script execution**:

1. Read `tokens` and `last_refill`.
2. Initialize bucket if it does not exist (start full).
3. Refill based on elapsed time.
4. Decide allow/deny.
5. Update `tokens` and `last_refill`.
6. Set a TTL for automatic cleanup.
7. Return the decision and remaining tokens.

Because Redis runs Lua scripts **atomically** and single-threaded per shard, this guarantees:

- No race conditions between concurrent requests for the same client.
- A single, strong source of truth for the rate-limiter state.

### Cleanup of Inactive Clients

The Lua script sets a TTL based on:

- `TTL = floor(capacity / refill_rate)` seconds.

This approximates the time it takes for an empty bucket to refill to capacity. Every time a client makes a request, the script refreshes the TTL. Clients that stop sending traffic eventually **age out** and their keys are removed automatically by Redis, preventing unbounded key growth.

### Redis Failure Handling (Fail-Open vs Fail-Closed)

The behavior when Redis is unavailable or the Lua script fails is controlled by:

- `rate-limiter.fail-open-on-redis-error` (boolean, default: `true`)

#### Fail-open (default)

- Configuration: `rate-limiter.fail-open-on-redis-error: true`
- Behavior:
  - If Redis is down, slow, or script evaluation fails, the `RateLimiterService`:
    - logs the error with context, and
    - returns a result that **allows** the request but marks it as `degraded`.
  - The HTTP filter (`RateLimitingFilter`) lets the request proceed as normal.
  - Optionally, responses can include `X-RateLimit-Degraded: true` to signal that rate limiting was not enforced.
- Trade-off:
  - **Pro**: preserves availability of your upstream services when Redis is degraded.
  - **Con**: while Redis is down, rate limits are **not** enforced; clients can exceed their configured quotas.

This mode is usually preferred when protecting user-facing APIs where **uptime is more critical** than strict enforcement.

#### Fail-closed

- Configuration: `rate-limiter.fail-open-on-redis-error: false`
- Behavior:
  - If Redis is down, slow, or script evaluation fails, the `RateLimiterService`:
    - returns a `REJECT_REDIS_FAILURE` decision.
  - The HTTP filter responds with:
    - `HTTP 503 Service Unavailable`
    - Response body: `Service temporarily unavailable (rate limiter backend error)`
  - Requests are **blocked** until Redis recovers.
- Trade-off:
  - **Pro**: protects backend systems by never allowing traffic that cannot be reliably rate-limited.
  - **Con**: rate-limiter outages surface as **outages for the protected APIs**.

This mode is appropriate for internal or sensitive systems where **control and safety** are more important than raw availability (e.g. protecting expensive downstream systems or regulatory constraints).

### Running Locally

Assuming a local Redis instance is available on `localhost:6379`, you can run:

```bash
mvn spring-boot:run
```

and issue requests against:

- `http://localhost:8080/...` (any endpoint; the `RateLimitingFilter` applies globally).

The repository also ships with a **Docker + Docker Compose** setup for local runs:

- Build + run app and Redis:

```bash
docker-compose up --build
```

- Call the sample endpoint (rate-limited globally per client id):

```bash
curl -H "X-API-Key: demo-key" http://localhost:8080/api/ping
```

By default, failures talking to Redis will **fail-open** (`fail-open-on-redis-error: true`) so that the application remains available even if the rate-limiter backend is degraded. Set this flag to `false` if you prefer a **fail-closed** posture, where Redis outages surface as `503` responses instead of allowing traffic to bypass the limiter.



