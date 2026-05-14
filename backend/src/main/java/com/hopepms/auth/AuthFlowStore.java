package com.hopepms.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * One-shot Redis store for the OAuth flow state.
 *
 * Holds the PKCE verifier + post-login `returnTo` URL keyed by the random
 * `state` parameter. Entries expire after 10 minutes (Auth0's authorize
 * round-trip should never take that long).
 */
@Component
public class AuthFlowStore {

    private static final String PREFIX = "hpms:authflow:";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public AuthFlowStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public void save(String state, String verifier, String returnTo) {
        try {
            String payload = json.writeValueAsString(Map.of(
                    "verifier", verifier,
                    "returnTo", returnTo == null ? "/products" : returnTo
            ));
            redis.opsForValue().set(PREFIX + state, payload, TTL);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist auth flow state", e);
        }
    }

    public Optional<FlowEntry> consume(String state) {
        String key = PREFIX + state;
        String raw = redis.opsForValue().get(key);
        if (raw == null) return Optional.empty();
        redis.delete(key);
        try {
            Map<String, String> m = json.readValue(raw, new TypeReference<>() {});
            return Optional.of(new FlowEntry(m.get("verifier"), m.get("returnTo")));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public record FlowEntry(String verifier, String returnTo) {}
}
