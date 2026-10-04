package io.github.giuliogirardi.ratelimiter.limiter;

import io.github.giuliogirardi.ratelimiter.config.RateLimiterProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Runs the real Lua script against a real Redis to verify the token bucket semantics.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisTokenBucketRateLimiterIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void flushRedis() {
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    @Test
    void allowsBurstUpToCapacityThenRejectsWithRetryAfter() {
        RateLimiter limiter = limiter(5, 1);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume("client").decision()).isEqualTo(RateLimitDecision.ALLOW);
        }
        RateLimitResult rejected = limiter.tryConsume("client");

        assertThat(rejected.decision()).isEqualTo(RateLimitDecision.REJECT_RATE_LIMITED);
        assertThat(rejected.remainingTokens()).isLessThan(1);
        assertThat(rejected.retryAfterMillis()).isBetween(1L, 1000L);
    }

    @Test
    void reportsFractionalRemainingTokensWithoutTruncation() {
        RateLimitResult result = limiter(3.5, 0.001).tryConsume("client");

        assertThat(result.remainingTokens()).isCloseTo(2.5, within(0.01));
    }

    @Test
    void refillsTokensOverTime() throws InterruptedException {
        RateLimiter limiter = limiter(1, 10);

        assertThat(limiter.tryConsume("client").decision()).isEqualTo(RateLimitDecision.ALLOW);
        assertThat(limiter.tryConsume("client").decision()).isEqualTo(RateLimitDecision.REJECT_RATE_LIMITED);

        Thread.sleep(150);

        assertThat(limiter.tryConsume("client").decision()).isEqualTo(RateLimitDecision.ALLOW);
    }

    @Test
    void keepsSeparateBucketsPerClient() {
        RateLimiter limiter = limiter(1, 0.001);

        assertThat(limiter.tryConsume("alice").decision()).isEqualTo(RateLimitDecision.ALLOW);
        assertThat(limiter.tryConsume("alice").decision()).isEqualTo(RateLimitDecision.REJECT_RATE_LIMITED);
        assertThat(limiter.tryConsume("bob").decision()).isEqualTo(RateLimitDecision.ALLOW);
    }

    @Test
    void neverAllowsMoreThanCapacityUnderConcurrency() throws Exception {
        int capacity = 50;
        int threads = 32;
        int requestsPerThread = 10;
        RateLimiter limiter = limiter(capacity, 0.001);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(burst(limiter, startGate, requestsPerThread)));
            }
            startGate.countDown();

            int totalAllowed = 0;
            for (Future<Integer> future : futures) {
                totalAllowed += future.get(30, TimeUnit.SECONDS);
            }

            assertThat(totalAllowed).isEqualTo(capacity);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void setsTtlSoIdleBucketsExpire() {
        limiter(2, 4).tryConsume("client");

        Long ttlMillis = redisTemplate.getExpire("rate_limiter:client", TimeUnit.MILLISECONDS);

        // capacity / refill rate = 0.5s until an idle bucket is full again.
        assertThat(ttlMillis).isBetween(1L, 500L);
    }

    @Test
    void resetsCorruptedBucketState() {
        redisTemplate.opsForHash().put("rate_limiter:client", "tokens", "not-a-number");

        RateLimitResult result = limiter(3, 1).tryConsume("client");

        assertThat(result.decision()).isEqualTo(RateLimitDecision.ALLOW);
        assertThat(result.remainingTokens()).isCloseTo(2, within(0.01));
    }

    @Test
    void scriptRejectsInvalidArgumentsInsteadOfStoringBadState() {
        // Bypasses property validation on purpose: the Lua script must defend itself too.
        RateLimiter limiter = limiter(1, 1, 2);

        assertThat(limiter.tryConsume("client").decision()).isEqualTo(RateLimitDecision.REJECT_BACKEND_FAILURE);
        assertThat(redisTemplate.hasKey("rate_limiter:client")).isFalse();
    }

    private static Callable<Integer> burst(RateLimiter limiter, CountDownLatch startGate, int requests) {
        return () -> {
            startGate.await();
            int allowed = 0;
            for (int i = 0; i < requests; i++) {
                if (limiter.tryConsume("hot-client").decision() == RateLimitDecision.ALLOW) {
                    allowed++;
                }
            }
            return allowed;
        };
    }

    private static RateLimiter limiter(double capacity, double refillRatePerSecond) {
        return limiter(capacity, refillRatePerSecond, 1);
    }

    private static RateLimiter limiter(double capacity, double refillRatePerSecond, double cost) {
        var properties = new RateLimiterProperties(capacity, refillRatePerSecond, cost, false, List.of());
        return new RedisTokenBucketRateLimiter(redisTemplate, properties);
    }
}
