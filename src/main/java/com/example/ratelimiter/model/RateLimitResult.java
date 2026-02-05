package com.example.ratelimiter.model;

/**
 * Result returned by the rate limiter for a single request.
 */
public class RateLimitResult {

    private final RateLimitDecision decision;
    private final double remainingTokens;
    private final boolean degraded;

    public RateLimitResult(RateLimitDecision decision, double remainingTokens, boolean degraded) {
        this.decision = decision;
        this.remainingTokens = remainingTokens;
        this.degraded = degraded;
    }

    public static RateLimitResult allow(double remainingTokens, boolean degraded) {
        return new RateLimitResult(RateLimitDecision.ALLOW, remainingTokens, degraded);
    }

    public static RateLimitResult rejectRateLimited(double remainingTokens) {
        return new RateLimitResult(RateLimitDecision.REJECT_RATE_LIMITED, remainingTokens, false);
    }

    public static RateLimitResult rejectRedisFailure() {
        return new RateLimitResult(RateLimitDecision.REJECT_REDIS_FAILURE, 0.0, true);
    }

    public RateLimitDecision getDecision() {
        return decision;
    }

    public double getRemainingTokens() {
        return remainingTokens;
    }

    /**
     * @return true if the result was produced while the system was in a degraded mode
     * (for example, Redis was unavailable and we had to fall back to a fail-open policy).
     */
    public boolean isDegraded() {
        return degraded;
    }
}


