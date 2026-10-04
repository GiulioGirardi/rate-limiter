package io.github.giuliogirardi.ratelimiter.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Rate limiter settings, bound from the {@code rate-limiter.*} namespace and validated at startup.
 *
 * @param capacity             maximum tokens a bucket can hold (burst size)
 * @param refillRatePerSecond  tokens added to a bucket per second (sustained rate)
 * @param costPerRequest       tokens consumed by each request
 * @param failOpenOnRedisError if true, requests are allowed when Redis fails; if false, they get HTTP 503
 * @param excludedPaths        Ant-style paths that are never rate limited (e.g. health checks)
 */
@Validated
@ConfigurationProperties(prefix = "rate-limiter")
public record RateLimiterProperties(
        @Positive double capacity,
        @Positive double refillRatePerSecond,
        @DefaultValue("1") @Positive double costPerRequest,
        @DefaultValue("false") boolean failOpenOnRedisError,
        @DefaultValue("/actuator/**") @NotNull List<String> excludedPaths
) {

    @AssertTrue(message = "cost-per-request must not exceed capacity, otherwise no request could ever be allowed")
    boolean isCostWithinCapacity() {
        return costPerRequest <= capacity;
    }
}
