package io.github.giuliogirardi.ratelimiter.limiter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MeteredRateLimiterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    static Stream<Arguments> outcomes() {
        return Stream.of(
                Arguments.of(RateLimitResult.allowed(1), "allowed"),
                Arguments.of(RateLimitResult.rateLimited(0, 500), "rate_limited"),
                Arguments.of(RateLimitResult.allowedDegraded(), "allowed_degraded"),
                Arguments.of(RateLimitResult.backendFailure(), "backend_failure")
        );
    }

    @ParameterizedTest
    @MethodSource("outcomes")
    void countsEachDecisionUnderItsOutcomeTag(RateLimitResult result, String outcome) {
        RateLimiter limiter = new MeteredRateLimiter(clientKey -> result, registry);

        limiter.tryConsume("client");
        limiter.tryConsume("client");

        assertThat(registry.get(MeteredRateLimiter.METRIC_NAME).tag("outcome", outcome).counter().count())
                .isEqualTo(2.0);
    }

    @Test
    void returnsDelegateResultUnchanged() {
        RateLimitResult expected = RateLimitResult.rateLimited(0.5, 1200);

        RateLimitResult actual = new MeteredRateLimiter(clientKey -> expected, registry).tryConsume("client");

        assertThat(actual).isSameAs(expected);
    }
}
