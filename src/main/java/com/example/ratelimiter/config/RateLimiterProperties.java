package com.example.ratelimiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rate-limiter")
public class RateLimiterProperties {

    /**
     * Maximum number of tokens a bucket can hold (burst capacity).
     */
    private double capacity;

    /**
     * Tokens refilled per second.
     */
    private double refillRatePerSecond;

    /**
     * Tokens consumed by each request.
     */
    private double costPerRequest;

    /**
     * If true, requests are allowed when Redis is unavailable or the Lua script fails.
     * If false, requests are rejected when we cannot reliably enforce limits.
     */
    private boolean failOpenOnRedisError;

    public double getCapacity() {
        return capacity;
    }

    public void setCapacity(double capacity) {
        this.capacity = capacity;
    }

    public double getRefillRatePerSecond() {
        return refillRatePerSecond;
    }

    public void setRefillRatePerSecond(double refillRatePerSecond) {
        this.refillRatePerSecond = refillRatePerSecond;
    }

    public double getCostPerRequest() {
        return costPerRequest;
    }

    public void setCostPerRequest(double costPerRequest) {
        this.costPerRequest = costPerRequest;
    }

    public boolean isFailOpenOnRedisError() {
        return failOpenOnRedisError;
    }

    public void setFailOpenOnRedisError(boolean failOpenOnRedisError) {
        this.failOpenOnRedisError = failOpenOnRedisError;
    }
}


