package com.example.ratelimiter.service;

import com.example.ratelimiter.config.RateLimiterProperties;
import com.example.ratelimiter.model.RateLimitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Collections;
import java.util.List;

/**
 * Stateless service that evaluates rate limits for a given client identifier by
 * delegating to a Lua script running inside Redis.
 *
 * All concurrency control lives inside the Lua script. This service is mostly responsible for:
 *  - building the correct Redis key
 *  - passing parameters
 *  - translating the script result into a domain-level decision
 *  - defining the behavior when Redis is unavailable (fail-open vs fail-closed)
 */
@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> rateLimiterScript;
    private final RateLimiterProperties properties;
    private final Clock clock;


    @Autowired
    public RateLimiterService(
            StringRedisTemplate redisTemplate,
            DefaultRedisScript<List> rateLimiterScript,
            RateLimiterProperties properties,
            Clock clock
    ) {
        this.redisTemplate = redisTemplate;
        this.rateLimiterScript = rateLimiterScript;
        this.properties = properties;
        this.clock = clock;
    }


    /**
     * Evaluate whether the client is allowed to perform one request at this time.
     *
     * @param clientId unique identifier for the client (e.g. API key, userId, IP).
     */
    public RateLimitResult check(String clientId) {
        String key = "rate_limiter:" + clientId;

        List<String> keys = Collections.singletonList(key);
        String capacity = Double.toString(properties.getCapacity());
        String refillRate = Double.toString(properties.getRefillRatePerSecond());
        String cost = Double.toString(properties.getCostPerRequest());
        String now = Long.toString(clock.millis());

        try {
            Object result = redisTemplate.execute(
                    rateLimiterScript,
                    keys,
                    capacity,
                    refillRate,
                    cost,
                    now
            );

            if (!(result instanceof List<?> listResult) || listResult.size() < 2) {
                // Defensive: unexpected script return type; treat as infrastructure failure.
                log.error("Unexpected Lua script result for client {}: {}", clientId, result);
                return handleRedisFailure();
            }

            Object allowedRaw = listResult.get(0);
            Object tokensRaw = listResult.get(1);

            long allowedFlag = toLong(allowedRaw);
            double remainingTokens = toDouble(tokensRaw);

            if (allowedFlag == 1L) {
                return RateLimitResult.allow(remainingTokens, false);
            } else {
                return RateLimitResult.rejectRateLimited(remainingTokens);
            }
        } catch (RedisConnectionFailureException ex) {
            log.warn("Redis connection failure while evaluating rate limit for client {}. failOpenOnRedisError={}",
                    clientId, properties.isFailOpenOnRedisError(), ex);
            return handleRedisFailure();
        } catch (DataAccessException ex) {
            // Catches script execution and other Redis-related errors.
            log.error("Redis data access error while evaluating rate limit for client {}. failOpenOnRedisError={}",
                    clientId, properties.isFailOpenOnRedisError(), ex);
            return handleRedisFailure();
        } catch (RuntimeException ex) {
            // Last resort; do not let rate limiting crash the request thread.
            log.error("Unexpected error while evaluating rate limit for client {}. failOpenOnRedisError={}",
                    clientId, properties.isFailOpenOnRedisError(), ex);
            return handleRedisFailure();
        }
    }

    private RateLimitResult handleRedisFailure() {
        if (properties.isFailOpenOnRedisError()) {
            // Fail-open: preserve availability of the protected service at the cost of losing
            // enforcement guarantees while Redis is degraded.
            return RateLimitResult.allow(Double.NaN, true);
        } else {
            // Fail-closed: prioritize enforcement guarantees over availability.
            return RateLimitResult.rejectRedisFailure();
        }
    }

    private long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }
}


