package io.github.giuliogirardi.ratelimiter.limiter;

/**
 * High-level outcome of a single rate-limit evaluation.
 */
public enum RateLimitDecision {

    /**
     * The request is within the configured limits and may proceed.
     */
    ALLOW,

    /**
     * The client exhausted its quota; the request must be rejected with HTTP 429.
     */
    REJECT_RATE_LIMITED,

    /**
     * The limiter backend is unavailable and the limiter is configured to fail closed (HTTP 503).
     */
    REJECT_BACKEND_FAILURE
}
