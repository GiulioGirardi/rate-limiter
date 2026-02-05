package com.example.ratelimiter.filter;

import com.example.ratelimiter.model.RateLimitDecision;
import com.example.ratelimiter.model.RateLimitResult;
import com.example.ratelimiter.service.RateLimiterService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that applies rate limiting to every incoming HTTP request.
 *
 * The filter is deliberately simple: it delegates all decision-making to {@link RateLimiterService}
 * and translates the result into HTTP semantics (429 or 503).
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    private final RateLimiterService rateLimiterService;

    public RateLimitingFilter(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String clientId = extractClientId(request);

        RateLimitResult result = rateLimiterService.check(clientId);

        if (result.getDecision() == RateLimitDecision.ALLOW) {
            // Optionally expose remaining tokens / degraded mode via headers for observability.
            if (result.isDegraded()) {
                response.setHeader("X-RateLimit-Degraded", "true");
            }
            filterChain.doFilter(request, response);
            return;
        }

        if (result.getDecision() == RateLimitDecision.REJECT_RATE_LIMITED) {
            // Standard 429 semantics when the client has exceeded its budget.
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            // Retry-After is intentionally simple here; for a precise value we would
            // compute how long until the bucket refills enough for one token.
            response.setHeader(HttpHeaders.RETRY_AFTER, "1");
            response.getWriter().write("Too Many Requests");
            return;
        }

        if (result.getDecision() == RateLimitDecision.REJECT_REDIS_FAILURE) {
            // When configured to fail closed, we reject the request with a 503 and
            // make the root cause explicit in logs and the response body.
            log.error("Rejecting request for client {} due to Redis failure and fail-closed configuration", clientId);
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.getWriter().write("Service temporarily unavailable (rate limiter backend error)");
            return;
        }

        // Defensive: in case of an unrecognized decision, preserve availability.
        filterChain.doFilter(request, response);
    }

    private String extractClientId(HttpServletRequest request) {
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return "api-key:" + apiKey;
        }
        String ip = request.getRemoteAddr();
        return "ip:" + ip;
    }
}


