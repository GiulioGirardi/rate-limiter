package io.github.giuliogirardi.ratelimiter.config;

import io.github.giuliogirardi.ratelimiter.limiter.RateLimiter;
import io.github.giuliogirardi.ratelimiter.limiter.RedisTokenBucketRateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(RateLimiterProperties.class)
public class RateLimiterConfiguration {

    @Bean
    public RateLimiter rateLimiter(StringRedisTemplate redisTemplate, RateLimiterProperties properties) {
        return new RedisTokenBucketRateLimiter(redisTemplate, properties);
    }
}
