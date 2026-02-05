package com.example.ratelimiter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Clock;
import java.util.List;

@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * Lua script implementing the token bucket algorithm.
     * <p>
     * Loaded once at startup and cached by Spring/Data Redis.
     */
    @Bean
    public DefaultRedisScript<List> rateLimiterScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/rate_limiter.lua"));
        script.setResultType(List.class);
        return script;
    }

    /**
     * Clock bean for time-based operations in the rate limiter.
     * <p>
     * Using UTC clock for consistent time across distributed instances.
     * Can be overridden in tests with a fixed clock.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}


