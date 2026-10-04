package io.github.giuliogirardi.ratelimiter.limiter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Decorator that records every decision of the wrapped {@link RateLimiter} as a Micrometer counter
 * ({@code rate_limiter.decisions}, tagged by {@code outcome}), keeping metrics out of the algorithm.
 */
public class MeteredRateLimiter implements RateLimiter {

    static final String METRIC_NAME = "rate_limiter.decisions";

    private final RateLimiter delegate;
    private final Counter allowed;
    private final Counter rateLimited;
    private final Counter degraded;
    private final Counter backendFailure;

    public MeteredRateLimiter(RateLimiter delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.allowed = counter(registry, "allowed");
        this.rateLimited = counter(registry, "rate_limited");
        this.degraded = counter(registry, "allowed_degraded");
        this.backendFailure = counter(registry, "backend_failure");
    }

    @Override
    public RateLimitResult tryConsume(String clientKey) {
        RateLimitResult result = delegate.tryConsume(clientKey);
        counterFor(result).increment();
        return result;
    }

    private Counter counterFor(RateLimitResult result) {
        return switch (result.decision()) {
            case ALLOW -> result.degraded() ? degraded : allowed;
            case REJECT_RATE_LIMITED -> rateLimited;
            case REJECT_BACKEND_FAILURE -> backendFailure;
        };
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder(METRIC_NAME)
                .description("Rate limiter decisions by outcome")
                .tag("outcome", outcome)
                .register(registry);
    }
}
