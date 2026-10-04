package io.github.giuliogirardi.ratelimiter.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Derives the identity a request is rate limited by.
 * <p>
 * Swap the implementation to limit by authenticated user, tenant, route, etc.
 */
@FunctionalInterface
public interface ClientKeyResolver {

    String resolve(HttpServletRequest request);
}
