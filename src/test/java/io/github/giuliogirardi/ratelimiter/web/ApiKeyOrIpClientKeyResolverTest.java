package io.github.giuliogirardi.ratelimiter.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyOrIpClientKeyResolverTest {

    private final ApiKeyOrIpClientKeyResolver resolver = new ApiKeyOrIpClientKeyResolver();

    @Test
    void usesHashedApiKeyWhenPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Key", "secret-key");

        String clientKey = resolver.resolve(request);

        assertThat(clientKey)
                .startsWith("key:")
                .doesNotContain("secret-key")
                .hasSize("key:".length() + 64);
    }

    @Test
    void sameApiKeyAlwaysMapsToSameClient() {
        MockHttpServletRequest first = new MockHttpServletRequest();
        first.addHeader("X-API-Key", "secret-key");
        MockHttpServletRequest second = new MockHttpServletRequest();
        second.addHeader("X-API-Key", "secret-key");

        assertThat(resolver.resolve(first)).isEqualTo(resolver.resolve(second));
    }

    @Test
    void fallsBackToRemoteIpWhenApiKeyIsBlank() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Key", "  ");
        request.setRemoteAddr("203.0.113.7");

        assertThat(resolver.resolve(request)).isEqualTo("ip:203.0.113.7");
    }
}
