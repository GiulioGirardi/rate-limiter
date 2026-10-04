package io.github.giuliogirardi.ratelimiter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.giuliogirardi.ratelimiter.config.RateLimiterProperties;
import io.github.giuliogirardi.ratelimiter.limiter.RateLimitResult;
import io.github.giuliogirardi.ratelimiter.limiter.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;

/**
 * Servlet filter that applies rate limiting to every incoming request before it reaches a controller.
 * <p>
 * It only translates {@link RateLimitResult}s into HTTP: {@code X-RateLimit-*} headers on every
 * response, {@code 429} with an accurate {@code Retry-After} when the quota is exhausted and
 * {@code 503} when the backend failed in fail-closed mode. Error bodies follow RFC 9457 (problem+json).
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    static final String LIMIT_HEADER = "X-RateLimit-Limit";
    static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    static final String DEGRADED_HEADER = "X-RateLimit-Degraded";

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final UrlPathHelper URL_PATH_HELPER = new UrlPathHelper();

    private final RateLimiter rateLimiter;
    private final ClientKeyResolver clientKeyResolver;
    private final RateLimiterProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitingFilter(
            RateLimiter rateLimiter,
            ClientKeyResolver clientKeyResolver,
            RateLimiterProperties properties,
            ObjectMapper objectMapper
    ) {
        this.rateLimiter = rateLimiter;
        this.clientKeyResolver = clientKeyResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = URL_PATH_HELPER.getPathWithinApplication(request);
        return properties.excludedPaths().stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        RateLimitResult result = rateLimiter.tryConsume(clientKeyResolver.resolve(request));
        writeRateLimitHeaders(result, response);

        switch (result.decision()) {
            case ALLOW -> filterChain.doFilter(request, response);
            case REJECT_RATE_LIMITED -> {
                response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(result)));
                writeProblem(response, HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded, retry after the Retry-After delay");
            }
            case REJECT_BACKEND_FAILURE ->
                    writeProblem(response, HttpStatus.SERVICE_UNAVAILABLE, "Rate limiter backend is temporarily unavailable");
        }
    }

    private void writeRateLimitHeaders(RateLimitResult result, HttpServletResponse response) {
        response.setHeader(LIMIT_HEADER, Long.toString((long) properties.capacity()));
        if (result.degraded()) {
            response.setHeader(DEGRADED_HEADER, "true");
        } else {
            response.setHeader(REMAINING_HEADER, Long.toString((long) Math.floor(result.remainingTokens())));
        }
    }

    private static long retryAfterSeconds(RateLimitResult result) {
        // Retry-After only supports whole seconds; round up so clients never retry too early.
        return Math.max(1, (result.retryAfterMillis() + 999) / 1000);
    }

    private void writeProblem(HttpServletResponse response, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(status, detail));
    }
}
