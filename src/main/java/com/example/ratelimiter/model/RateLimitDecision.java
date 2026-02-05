package com.example.ratelimiter.model;

/**
 * High-level outcome of a single rate-limit evaluation.
 */
public enum RateLimitDecision {
    /**
     * Request is within the configured limits and may proceed.
     */
    ALLOW,

    /**
     * Request exceeds the configured limits and must be rejected with 429.
     */
    REJECT_RATE_LIMITED,

    /**
     * Internal infrastructure issue (e.g. Redis unavailable) and we are configured to fail closed.
     */
    REJECT_REDIS_FAILURE
}


