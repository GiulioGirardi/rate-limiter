package io.github.giuliogirardi.ratelimiter.limiter;

/**
 * Result of a single rate-limit evaluation.
 *
 * @param decision         what should happen to the request
 * @param remainingTokens  tokens left in the bucket after this request ({@code NaN} when unknown)
 * @param retryAfterMillis time until the request could succeed (only meaningful when rate limited)
 * @param degraded         true when the backend failed and the limiter fell back to its failure policy
 */
public record RateLimitResult(
        RateLimitDecision decision,
        double remainingTokens,
        long retryAfterMillis,
        boolean degraded
) {

    public static RateLimitResult allowed(double remainingTokens) {
        return new RateLimitResult(RateLimitDecision.ALLOW, remainingTokens, 0, false);
    }

    public static RateLimitResult rateLimited(double remainingTokens, long retryAfterMillis) {
        return new RateLimitResult(RateLimitDecision.REJECT_RATE_LIMITED, remainingTokens, retryAfterMillis, false);
    }

    /**
     * Fail-open: the backend failed, so the request is let through without enforcement.
     */
    public static RateLimitResult allowedDegraded() {
        return new RateLimitResult(RateLimitDecision.ALLOW, Double.NaN, 0, true);
    }

    /**
     * Fail-closed: the backend failed, so the request is rejected.
     */
    public static RateLimitResult backendFailure() {
        return new RateLimitResult(RateLimitDecision.REJECT_BACKEND_FAILURE, Double.NaN, 0, true);
    }
}
