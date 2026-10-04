package io.github.giuliogirardi.ratelimiter.limiter;

import io.github.giuliogirardi.ratelimiter.config.RateLimiterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Token bucket rate limiter whose state lives in Redis.
 * <p>
 * Each decision is a single {@code EVALSHA} of {@code lua/token_bucket.lua}: refill, check, deduct
 * and persist happen atomically inside Redis, so concurrent requests from any number of
 * application instances can never race on the same bucket.
 * <p>
 * When Redis is unavailable the configured failure policy applies: fail-open lets the request
 * through (flagged as degraded), fail-closed rejects it.
 */
public class RedisTokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    static final String KEY_PREFIX = "rate_limiter:";

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final RedisScript<List<Object>> TOKEN_BUCKET_SCRIPT =
            (RedisScript) RedisScript.of(new ClassPathResource("lua/token_bucket.lua"), List.class);

    private final StringRedisTemplate redisTemplate;
    private final RateLimiterProperties properties;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redisTemplate, RateLimiterProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public RateLimitResult tryConsume(String clientKey) {
        try {
            List<Object> reply = redisTemplate.execute(
                    TOKEN_BUCKET_SCRIPT,
                    List.of(KEY_PREFIX + clientKey),
                    Double.toString(properties.capacity()),
                    Double.toString(properties.refillRatePerSecond()),
                    Double.toString(properties.costPerRequest())
            );
            return toResult(reply);
        } catch (DataAccessException ex) {
            // Expected during Redis outages: keep it to one line per request, no stack trace.
            log.warn("Rate limiter backend unavailable ({}), failOpen={}", ex.getMessage(), properties.failOpenOnRedisError());
            return onBackendFailure();
        } catch (RuntimeException ex) {
            log.error("Unexpected rate limiter failure, failOpen={}", properties.failOpenOnRedisError(), ex);
            return onBackendFailure();
        }
    }

    private RateLimitResult onBackendFailure() {
        return properties.failOpenOnRedisError()
                ? RateLimitResult.allowedDegraded()
                : RateLimitResult.backendFailure();
    }

    private static RateLimitResult toResult(List<Object> reply) {
        if (reply == null || reply.size() < 3) {
            throw new IllegalStateException("Unexpected token bucket script reply: " + reply);
        }
        boolean allowed = Long.parseLong(reply.get(0).toString()) == 1L;
        double remainingTokens = Double.parseDouble(reply.get(1).toString());
        long retryAfterMillis = Long.parseLong(reply.get(2).toString());

        return allowed
                ? RateLimitResult.allowed(remainingTokens)
                : RateLimitResult.rateLimited(remainingTokens, retryAfterMillis);
    }
}
