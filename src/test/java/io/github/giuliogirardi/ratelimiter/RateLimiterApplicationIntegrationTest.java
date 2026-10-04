package io.github.giuliogirardi.ratelimiter;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end: HTTP request -> filter -> Lua script in a real Redis -> HTTP response.
 */
@SpringBootTest(properties = {
        "rate-limiter.capacity=2",
        "rate-limiter.refill-rate-per-second=0.001"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RateLimiterApplicationIntegrationTest {

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void enforcesLimitPerApiKey() throws Exception {
        mockMvc.perform(get("/api/ping").header("X-API-Key", "end-to-end"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "2"))
                .andExpect(header().string("X-RateLimit-Remaining", "1"));

        mockMvc.perform(get("/api/ping").header("X-API-Key", "end-to-end"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "0"));

        mockMvc.perform(get("/api/ping").header("X-API-Key", "end-to-end"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429));

        mockMvc.perform(get("/api/ping").header("X-API-Key", "another-client"))
                .andExpect(status().isOk());
    }

    @Test
    void neverRateLimitsHealthChecks() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("X-RateLimit-Limit"));
        }
    }

    @Test
    void exposesDecisionsAsMetrics() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/ping").header("X-API-Key", "metrics"));
        }

        double rateLimited = meterRegistry.get("rate_limiter.decisions")
                .tag("outcome", "rate_limited").counter().count();
        assertThat(rateLimited).isGreaterThanOrEqualTo(1.0);
    }
}
