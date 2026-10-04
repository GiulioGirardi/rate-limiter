package io.github.giuliogirardi.ratelimiter.limiter;

import io.github.giuliogirardi.ratelimiter.config.RateLimiterProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisTokenBucketRateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Test
    void allowsWhenScriptGrantsToken() {
        givenScriptReplies(1L, "4.5", 0L);

        RateLimitResult result = limiter(false).tryConsume("client");

        assertThat(result).isEqualTo(RateLimitResult.allowed(4.5));
    }

    @Test
    void rejectsWithRetryAfterWhenBucketIsEmpty() {
        givenScriptReplies(0L, "0.25", 750L);

        RateLimitResult result = limiter(false).tryConsume("client");

        assertThat(result).isEqualTo(RateLimitResult.rateLimited(0.25, 750));
    }

    @Test
    void passesPrefixedKeyAndConfiguredParametersToScript() {
        givenScriptReplies(1L, "1", 0L);

        limiter(false).tryConsume("ip:10.0.0.1");

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("rate_limiter:ip:10.0.0.1")),
                eq("5.0"), eq("2.0"), eq("1.0"));
    }

    @Test
    void failOpenAllowsRequestAsDegradedWhenRedisIsDown() {
        givenRedisThrows(new RedisConnectionFailureException("connection refused"));

        RateLimitResult result = limiter(true).tryConsume("client");

        assertThat(result).isEqualTo(RateLimitResult.allowedDegraded());
    }

    @Test
    void failClosedRejectsRequestWhenRedisTimesOut() {
        givenRedisThrows(new QueryTimeoutException("timeout"));

        RateLimitResult result = limiter(false).tryConsume("client");

        assertThat(result).isEqualTo(RateLimitResult.backendFailure());
    }

    @Test
    void treatsMalformedScriptReplyAsBackendFailure() {
        givenScriptReplies(1L);

        RateLimitResult result = limiter(false).tryConsume("client");

        assertThat(result.decision()).isEqualTo(RateLimitDecision.REJECT_BACKEND_FAILURE);
    }

    private RedisTokenBucketRateLimiter limiter(boolean failOpen) {
        var properties = new RateLimiterProperties(5, 2, 1, failOpen, List.of());
        return new RedisTokenBucketRateLimiter(redisTemplate, properties);
    }

    @SuppressWarnings("unchecked")
    private void givenScriptReplies(Object... reply) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(reply));
    }

    @SuppressWarnings("unchecked")
    private void givenRedisThrows(RuntimeException ex) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenThrow(ex);
    }
}
