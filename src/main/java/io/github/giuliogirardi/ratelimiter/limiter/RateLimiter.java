package io.github.giuliogirardi.ratelimiter.limiter;

/**
 * Decides whether a client may perform one more request right now.
 * <p>
 * Implementations are free to choose the algorithm (token bucket, sliding window, ...)
 * and the backing store; callers only depend on the {@link RateLimitResult}.
 */
public interface RateLimiter {

    /**
     * Consumes one request's worth of quota for the given client, if available.
     *
     * @param clientKey stable identifier of the client (API key hash, IP, user id, ...)
     */
    RateLimitResult tryConsume(String clientKey);
}
