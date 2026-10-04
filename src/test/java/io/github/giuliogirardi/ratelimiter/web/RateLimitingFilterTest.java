package io.github.giuliogirardi.ratelimiter.web;

import io.github.giuliogirardi.ratelimiter.config.RateLimiterProperties;
import io.github.giuliogirardi.ratelimiter.limiter.RateLimitResult;
import io.github.giuliogirardi.ratelimiter.limiter.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitingFilterTest {

    private static final RateLimiterProperties PROPERTIES =
            new RateLimiterProperties(10, 1, 1, false, List.of("/actuator/**"));

    private final MockFilterChain chain = new MockFilterChain();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void allowedRequestProceedsWithRateLimitHeaders() throws Exception {
        filter(RateLimitResult.allowed(7.9)).doFilter(request("/api/ping"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("10");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("7");
    }

    @Test
    void rateLimitedRequestGets429WithRoundedUpRetryAfter() throws Exception {
        filter(RateLimitResult.rateLimited(0.4, 1_500)).doFilter(request("/api/ping"), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("2");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"status\":429", "\"title\":\"Too Many Requests\"");
    }

    @Test
    void retryAfterIsNeverBelowOneSecond() throws Exception {
        filter(RateLimitResult.rateLimited(0.99, 10)).doFilter(request("/api/ping"), response, chain);

        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    void backendFailureInFailClosedModeGets503() throws Exception {
        filter(RateLimitResult.backendFailure()).doFilter(request("/api/ping"), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("X-RateLimit-Degraded")).isEqualTo("true");
        assertThat(response.getContentAsString()).contains("\"status\":503");
    }

    @Test
    void degradedRequestInFailOpenModeProceedsAndIsFlagged() throws Exception {
        filter(RateLimitResult.allowedDegraded()).doFilter(request("/api/ping"), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getHeader("X-RateLimit-Degraded")).isEqualTo("true");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isNull();
    }

    @Test
    void excludedPathsBypassTheLimiter() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        RateLimiter limiter = clientKey -> {
            calls.incrementAndGet();
            return RateLimitResult.rateLimited(0, 1000);
        };

        filter(limiter).doFilter(request("/actuator/health"), response, chain);

        assertThat(calls).hasValue(0);
        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    private static RateLimitingFilter filter(RateLimitResult result) {
        return filter(clientKey -> result);
    }

    private static RateLimitingFilter filter(RateLimiter limiter) {
        return new RateLimitingFilter(limiter, new ApiKeyOrIpClientKeyResolver(), PROPERTIES,
                Jackson2ObjectMapperBuilder.json().build());
    }
}
