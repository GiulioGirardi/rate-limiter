package io.github.giuliogirardi.ratelimiter.config;

import io.github.giuliogirardi.ratelimiter.limiter.MeteredRateLimiter;
import io.github.giuliogirardi.ratelimiter.limiter.RateLimiter;
import io.github.giuliogirardi.ratelimiter.limiter.RedisTokenBucketRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(RateLimiterProperties.class)
public class RateLimiterConfiguration {

    /**
     * The Redis token bucket, decorated with Micrometer metrics.
     */
    @Bean
    public RateLimiter rateLimiter(
            StringRedisTemplate redisTemplate,
            RateLimiterProperties properties,
            MeterRegistry meterRegistry
    ) {
        return new MeteredRateLimiter(new RedisTokenBucketRateLimiter(redisTemplate, properties), meterRegistry);
    }
}
