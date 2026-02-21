# Architectural Review: Distributed Redis Token Bucket

## Verdict

The implementation **mostly follows** the target architecture (single Redis source of truth + one Lua-scripted decision per request), but it is **not fully production-safe** under strict distributed systems standards.

Key concerns:

1. Time is provided by each app node (`Clock.systemUTC()`), not Redis server time, so refill behavior depends on host clock quality.
2. Lua script can persist malformed state when incoming configuration values are invalid (no defensive validation for `capacity`, `cost`, etc.).
3. TTL policy is simplistic and may churn write load (`EXPIRE` every request) and does not preserve idle-expiration semantics for some edge configurations.
4. Client identity derivation (`X-API-Key` and fallback `remoteAddr`) is not hardened for real proxy topologies and can produce skewed fairness.

## Constraint-by-constraint check

1. **Distributed system**: Satisfied by design (stateless app + Redis shared backend).
2. **Redis single source of truth**: Satisfied; token state only in Redis hash.
3. **Atomic decisions**: Satisfied for bucket math and update because logic is in one Lua script execution.
4. **Atomicity via Lua**: Satisfied.
5. **Exactly one Redis call per request**: Satisfied in normal path (`redisTemplate.execute(...)` once).
6. **No multi-step GET/SET in app code**: Satisfied.
7. **Lua requirements**: Mostly satisfied (init, refill, cap, deduct, persist, TTL, return tuple).
8. **Concurrency safety**: Satisfied for Redis-side state transitions (Lua execution is atomic).
9. **Filter/interceptor before controller**: Satisfied (`OncePerRequestFilter`).
10. **Denied -> HTTP 429**: Satisfied for rate-limit denials; Redis-failure denial is 503 by policy.

## Deep technical notes

### Atomicity & race conditions

- There is no client-side read/modify/write sequence; script handles both state read and write in one EVAL context.
- Redis serializes script execution, so concurrent requests for the same key remain race-free.
- No obvious race condition in application code for decision correctness.

### TTL behavior

- TTL = `floor(capacity / refill_rate)` seconds when `refill_rate > 0`; no TTL otherwise.
- `EXPIRE` is executed every request, effectively extending key life under traffic.
- Rounding down may expire slightly earlier than full refill time; in very low rates this can be operationally surprising.
- If `refill_rate <= 0`, keys become non-expiring and can leak cardinality indefinitely.

### Time safety

- Negative elapsed time is clamped to 0 (good defensive check).
- However, time comes from each application node, not Redis (`ARGV[4]` from `Clock`), so cross-node drift impacts fairness/refill consistency.
- A fast clock can refill earlier; a slow clock can throttle harder.

### Failure policy

- Behavior is explicitly configurable:
  - fail-open => allow request with degraded flag.
  - fail-closed => reject with dedicated decision translated to HTTP 503.
- This is clearly implemented and logged, but the business trade-off must be explicitly accepted.

## Senior engineering criticisms likely in production review

1. **Clock source choice**: Using app wall clock in distributed limiter is a major reliability concern; Redis `TIME`-based script logic (or monotonic shared source) would be preferred.
2. **Input validation hardening**: No guardrails for zero/negative `capacity`, negative `cost`, NaN/infinite values.
3. **TTL strategy**: Constant TTL refresh via `EXPIRE` on every request increases write amplification and may not be ideal at high QPS.
4. **Identity strategy**: Rate limiting by raw `remoteAddr` is often wrong behind LB/proxies unless trusted-forward headers are handled.
5. **Operational visibility**: Limited observability (no metrics for allow/deny/degraded/script errors by key dimension).
6. **Script resilience**: No fallback for malformed stored hash values (e.g., non-numeric corruption) inside Lua.
7. **Retry-After accuracy**: Hardcoded `Retry-After: 1` is not aligned with actual refill delay.

## Final assessment

- **Architecture compliance**: strong but not perfect.
- **Atomicity model**: correct.
- **Distributed hardening quality**: moderate; key production concerns remain around time source, TTL policy edge cases, and operational robustness.

## 50k RPS across 10 instances: what breaks first?

At 50k requests/second total (~5k RPS per instance), the **first likely bottleneck is Redis single-thread command/script throughput and latency amplification**, not Java CPU.

Why this is first:

1. Every request performs one Lua execution that includes `HMGET` + token math + `HMSET` + `EXPIRE` in Redis.
2. Script execution is serialized per Redis event loop/core behavior, so sustained high-QPS script volume quickly pushes p99 latency up.
3. Once Redis latency rises, application threads queue behind Redis I/O, causing tail latency spikes and eventually elevated 429/503 outcomes depending on failure mode.

The **second failure mode** is hot-key contention (many requests mapped to the same client key), which worsens queueing for those buckets even before global saturation.

The **third** is operational: with `EXPIRE` on every request, write amplification increases CPU/network pressure at peak load.

In short: the architecture is correctness-oriented, but at this traffic level capacity planning must focus on Redis script throughput, key distribution, and headroom for latency bursts.


## Would Cloudflare, Stripe, or Kong engineers approve this as-is?

**Short answer: likely no (as-is), but they would approve the core direction.**

### What they would like

- Correct use of Redis + Lua for atomic token-bucket decisions.
- Single remote decision call per request path.
- Clear fail-open/fail-closed policy control.
- Early request filtering before controller work.

### Why they would still block production approval

1. **Time consistency is not platform-grade**
   - Mature platforms avoid per-node app clock as the canonical limiter time source in distributed fleets.
2. **SLO/observability gaps**
   - Missing first-class metrics for allow/deny/degraded/redis-error rates, script latency histograms, and hot-key detection.
3. **Traffic identity hardening is incomplete**
   - `remoteAddr` fallback is weak behind proxies/CDNs; production systems need trusted identity extraction and anti-spoofing controls.
4. **Scale posture is under-specified**
   - No explicit sharding/partitioning strategy, no capacity model, and no demonstrated behavior under traffic spikes/hot tenants.
5. **Operational safeguards are thin**
   - No circuit-breaker/backpressure strategy around Redis saturation, no clear timeout budgets, and no retry policy rationale.
6. **Protocol semantics are simplistic**
   - Static `Retry-After` and limited client feedback are below the bar for mature public API ecosystems.

### Bottom line by org style

- **Cloudflare-style review:** would push hard on edge identity correctness, abuse resistance, and globally consistent behavior under adversarial load.
- **Stripe-style review:** would require deterministic behavior, explicit failure-mode contracts, and strong telemetry/SLO ownership.
- **Kong-style review:** would expect policy configurability, plugin-grade observability, and proxy-aware identity correctness out of the box.

So: **architecturally promising, but not yet “approved for internet-scale production” by teams with those standards.**

## Top 3 highest-risk issues (ordered by severity)

1. **Distributed time inconsistency (app-node clock as source of truth)**
   - Refill correctness depends on clock quality/skew across instances, directly impacting fairness and enforcement correctness under scale.

2. **Redis saturation under one-script-per-request design at high throughput**
   - At sustained high RPS, script latency amplification in Redis can become the dominant bottleneck and cascade into upstream latency/error spikes.

3. **Weak identity extraction in proxy/CDN environments**
   - Fallback to `remoteAddr` can collapse many clients behind shared egress and distort fairness/abuse controls unless trusted identity handling is enforced.

