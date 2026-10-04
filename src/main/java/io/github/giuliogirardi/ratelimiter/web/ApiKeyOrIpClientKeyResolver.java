package io.github.giuliogirardi.ratelimiter.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Identifies clients by the {@value #API_KEY_HEADER} header, falling back to the remote IP.
 * <p>
 * API keys are SHA-256 hashed so raw secrets never end up in Redis keys or logs.
 * Behind a load balancer, enable {@code server.forward-headers-strategy} so the remote IP
 * reflects the real client instead of the proxy.
 */
@Component
public class ApiKeyOrIpClientKeyResolver implements ClientKeyResolver {

    static final String API_KEY_HEADER = "X-API-Key";

    @Override
    public String resolve(HttpServletRequest request) {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (StringUtils.hasText(apiKey)) {
            return "key:" + sha256Hex(apiKey);
        }
        return "ip:" + request.getRemoteAddr();
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", ex);
        }
    }
}
