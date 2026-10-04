package io.github.giuliogirardi.ratelimiter.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsDefaults() {
        contextRunner
                .withPropertyValues("rate-limiter.capacity=10", "rate-limiter.refill-rate-per-second=2")
                .run(context -> {
                    RateLimiterProperties properties = context.getBean(RateLimiterProperties.class);
                    assertThat(properties.costPerRequest()).isEqualTo(1.0);
                    assertThat(properties.failOpenOnRedisError()).isFalse();
                    assertThat(properties.excludedPaths()).containsExactly("/actuator/**");
                });
    }

    @Test
    void rejectsNonPositiveCapacity() {
        contextRunner
                .withPropertyValues("rate-limiter.capacity=0", "rate-limiter.refill-rate-per-second=2")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsMissingRefillRate() {
        contextRunner
                .withPropertyValues("rate-limiter.capacity=10")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsCostGreaterThanCapacity() {
        contextRunner
                .withPropertyValues(
                        "rate-limiter.capacity=2",
                        "rate-limiter.refill-rate-per-second=1",
                        "rate-limiter.cost-per-request=3")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("cost-per-request must not exceed capacity"));
    }

    @Configuration
    @EnableConfigurationProperties(RateLimiterProperties.class)
    static class PropertiesConfiguration {
    }
}
